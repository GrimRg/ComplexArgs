package com.grimrg.complexargs

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Persists commandline presets. DEFINITIONS (groups + option text + id) go to complexargs.json,
 * which by default lives under the project's .idea but can be redirected to any folder via a
 * per-project working-directory override (to share presets across branches).
 *
 * SELECTION state (which options are checked and their order) is deliberately NOT shared: it is
 * stored per-project in .idea/complexargs.selection.json (always the real project, never the
 * override), keyed by option id, and re-applied on load. So pointing at another branch's presets
 * pulls in its options/groups but keeps this checkout's own selection.
 *
 * An older flat format (a bare list of options) is migrated into a single "General" group.
 */
object ArgStore
{
    private const val FILE_NAME = "complexargs.json"
    private const val SELECTION_FILE = "complexargs.selection.json"
    private const val WORKING_DIR_KEY = "ComplexArgs.workingDir"
    private val LOG = Logger.getInstance(ArgStore::class.java)
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val combinedCache = ConcurrentHashMap<String, Pair<String, String>>()

    /** Bumped on every successful write in this process so [combinedFor] refreshes immediately even
     *  when two saves land in the same filesystem-timestamp tick (lastModified has ~1s granularity). */
    private val saveVersion = AtomicLong(0)

    fun workingDir(project: Project): File
    {
        val override = PropertiesComponent.getInstance(project).getValue(WORKING_DIR_KEY)
        if (!override.isNullOrBlank())
        {
            return File(override)
        }
        val base = project.basePath
        return if (base != null) File(base, ".idea") else fallbackDir()
    }

    /** Home for projects with no base path (default/light projects): a stable per-user config
     *  location, never the IDE process working directory. */
    private fun fallbackDir(): File = File(PathManager.getConfigDir().toFile(), "complexargs")

    fun setWorkingDir(project: Project, path: String?)
    {
        val pc = PropertiesComponent.getInstance(project)
        if (path.isNullOrBlank())
        {
            pc.unsetValue(WORKING_DIR_KEY)
        }
        else
        {
            pc.setValue(WORKING_DIR_KEY, path)
        }
        combinedCache.remove(project.locationHash)
        saveVersion.incrementAndGet()
    }

    fun file(project: Project): File = File(workingDir(project), FILE_NAME)

    private fun selectionFile(project: Project): File?
    {
        val base = project.basePath ?: return null
        return File(File(base, ".idea"), SELECTION_FILE)
    }

    /**
     * Combined commandline for the frequent read-only paths (toolbar update, launch). Cached per
     * project and invalidated by an in-process save counter plus the size+mtime of the definitions
     * and selection files, so it refreshes on any edit - even two within the same filesystem-timestamp
     * tick, or an out-of-process change by another Rider - while avoiding a full read+parse every tick.
     */
    fun combinedFor(project: Project): String
    {
        val f = file(project)
        val sel = selectionFile(project)
        val stamp = buildString {
            append(saveVersion.get()).append(':')
            append(if (f.exists()) f.lastModified() else 0L).append(':')
            append(if (f.exists()) f.length() else 0L).append(':')
            append(if (sel != null && sel.exists()) sel.lastModified() else 0L).append(':')
            append(if (sel != null && sel.exists()) sel.length() else 0L)
        }
        val key = project.locationHash
        combinedCache[key]?.let { if (it.first == stamp) return it.second }
        val combined = ArgCombiner.combineGroups(load(project))
        combinedCache[key] = stamp to combined
        return combined
    }

    fun load(project: Project): MutableList<ArgGroup>
    {
        val f = file(project)
        val groups: MutableList<ArgGroup> = if (!f.exists())
        {
            mutableListOf()
        }
        else
        {
            val text = try { f.readText() } catch (e: Exception) { LOG.warn("Failed to read presets from [${f.path}]", e); return mutableListOf() }
            try
            {
                if (isOldFlatFormat(text))
                {
                    migrateFlat(text)
                }
                else
                {
                    gson.fromJson(text, object : TypeToken<MutableList<ArgGroup>>() {}.type) ?: mutableListOf()
                }
            }
            catch (e: Exception)
            {
                LOG.warn("Failed to parse presets from [${f.path}]", e)
                mutableListOf()
            }
        }

        var assignedId = false
        val seenIds = HashSet<String>()
        for (group in groups)
        {
            for (option in group.options)
            {
                val id = option.id
                if (id.isNullOrBlank() || !seenIds.add(id))
                {
                    val fresh = UUID.randomUUID().toString()
                    option.id = fresh
                    seenIds.add(fresh)
                    assignedId = true
                }
            }
        }
        if (assignedId)
        {
            saveDefinitions(project, groups)
        }
        applySelection(project, groups)
        return groups
    }

    fun save(project: Project, groups: List<ArgGroup>)
    {
        saveDefinitions(project, groups)
        saveSelection(project, groups)
    }

    private fun saveDefinitions(project: Project, groups: List<ArgGroup>)
    {
        val f = file(project)
        try
        {
            val json = gson.toJson(groups)
            if (f.exists() && f.readText() == json)
            {
                return
            }
            f.parentFile?.mkdirs()
            writeAtomically(f, json)
            saveVersion.incrementAndGet()
        }
        catch (e: Exception)
        {
            LOG.warn("Failed to save presets to [${f.path}]", e)
        }
    }

    /** Writes via a uniquely-named temp file + atomic replace, so a concurrent reader never sees a
     *  half-written file and two processes sharing the work dir never collide on the temp file. */
    private fun writeAtomically(target: File, content: String)
    {
        val tmp = File.createTempFile(target.name, ".tmp", target.parentFile)
        try
        {
            tmp.writeText(content)
            try
            {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            catch (e: Exception)
            {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        finally
        {
            Files.deleteIfExists(tmp.toPath())
        }
    }

    private fun saveSelection(project: Project, groups: List<ArgGroup>)
    {
        val f = selectionFile(project) ?: return
        try
        {
            val map = LinkedHashMap<String, Int>()
            for (group in groups)
            {
                for (option in group.options)
                {
                    val id = option.id
                    if (!id.isNullOrBlank() && option.selectionOrder > 0)
                    {
                        map[id] = option.selectionOrder
                    }
                }
            }
            f.parentFile?.mkdirs()
            writeAtomically(f, gson.toJson(map))
            saveVersion.incrementAndGet()
        }
        catch (e: Exception)
        {
            LOG.warn("Failed to save selection to [${f.path}]", e)
        }
    }

    private fun loadSelection(project: Project): Map<String, Int>
    {
        val f = selectionFile(project) ?: return emptyMap()
        if (!f.exists())
        {
            return emptyMap()
        }
        return try
        {
            gson.fromJson(f.readText(), object : TypeToken<Map<String, Int>>() {}.type) ?: emptyMap()
        }
        catch (e: Exception)
        {
            LOG.warn("Failed to load selection from [${f.path}]", e)
            emptyMap()
        }
    }

    private fun applySelection(project: Project, groups: List<ArgGroup>)
    {
        val selection = loadSelection(project)
        for (group in groups)
        {
            for (option in group.options)
            {
                val id = option.id
                option.selectionOrder = if (!id.isNullOrBlank()) selection[id] ?: 0 else 0
            }
        }
    }

    private fun isOldFlatFormat(text: String): Boolean
    {
        return try
        {
            val array = JsonParser.parseString(text).asJsonArray
            array.size() > 0 && array[0].isJsonObject &&
                array[0].asJsonObject.has("text") && !array[0].asJsonObject.has("options")
        }
        catch (e: Exception)
        {
            false
        }
    }

    private fun migrateFlat(text: String): MutableList<ArgGroup>
    {
        val optionType = object : TypeToken<MutableList<ArgOption>>() {}.type
        val options: MutableList<ArgOption> = gson.fromJson(text, optionType) ?: mutableListOf()
        return if (options.isEmpty()) mutableListOf() else mutableListOf(ArgGroup("General", true, options))
    }
}
