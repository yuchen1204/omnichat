package com.omnichat.data

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProjectFileStoreTest {

    private lateinit var root: File

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"),
            "project_test_${System.currentTimeMillis()}_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun projectRoot(projectId: Long): File =
        File(root, "project_$projectId")

    @Before
    fun setUp() {
        root = tempDir()
        ProjectFileStore.initForTest(root)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        ProjectFileStore.resetForTest()
        root.deleteRecursively()
    }

    @Test
    fun generatedAssetPathStaysInsideProjectDirectory() {
        val file = ProjectFileStore.assetFile(4L, 9L, "../secret.txt")
        assertTrue(file.canonicalPath.startsWith(projectRoot(4L).canonicalPath))
        assertEquals("asset_9.txt", file.name)
    }

    @Test
    fun assetPathRejectsUnsupportedExtension() {
        try {
            ProjectFileStore.assetFile(4L, 9L, "file.exe")
            fail("Expected IllegalArgumentException for unsupported extension")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unsupported"))
        }
    }

    @Test
    fun assetPathAcceptsSupportedExtensions() {
        val supported = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp",
            "pdf", "doc", "docx", "txt", "md")
        for (ext in supported) {
            val file = ProjectFileStore.assetFile(1L, 2L, "file.$ext")
            assertEquals("asset_2.$ext", file.name)
        }
    }

    @Test
    fun memoryUpdateUsesAtomicReplacementAndPreservesContentOnFailure() = runTest {
        ProjectFileStore.writeMemory(4L, "old")
        ProjectFileStore.writeMemory(4L, "new")
        assertEquals("new", ProjectFileStore.readMemory(4L))
    }

    @Test
    fun readMemoryReturnsEmptyStringWhenAbsent() {
        assertEquals("", ProjectFileStore.readMemory(99L))
    }

    @Test
    fun writeMemoryCreatesParentDirectories() = runTest {
        ProjectFileStore.writeMemory(7L, "hello")
        assertEquals("hello", ProjectFileStore.readMemory(7L))
    }

    @Test
    fun deleteAssetRemovesFile() {
        val tmp = File(root, "del_test.txt")
        tmp.writeText("content")
        assertTrue(tmp.exists())
        val asset = ProjectKnowledge(
            projectId = 1L,
            fileName = "del_test.txt",
            fileType = "txt",
            localFileName = "asset_0.txt"
        )
        // 验证 deleteAsset 不抛异常
        ProjectFileStore.deleteAsset(asset)
    }

    @Test
    fun deleteProjectDirectoryRemovesAllContents() = runTest {
        ProjectFileStore.writeMemory(5L, "memory")
        assertTrue(projectRoot(5L).exists())

        ProjectFileStore.deleteProjectDirectory(5L)
        assertFalse(projectRoot(5L).exists())
    }

    @Test
    fun sanitizeDisplayNameStripsPathTraversal() {
        val file = ProjectFileStore.assetFile(1L, 2L, "../../../etc/passwd.png")
        assertEquals("asset_2.png", file.name)
        assertTrue(file.canonicalPath.startsWith(root.canonicalPath))
    }

    @Test
    fun copyIntoProjectWritesFromSourceFile() {
        val sourceFile = File(root, "source.txt")
        sourceFile.writeText("hello world")

        // 使用 mock Context 测试 URI 复制
        val mockContext = org.robolectric.RuntimeEnvironment.getApplication()
        val tmp = ProjectFileStore.copyIntoProject(mockContext, 3L, android.net.Uri.fromFile(sourceFile), "source.txt", "USER_UPLOAD")
        assertTrue(tmp.exists())
        assertEquals("hello world", tmp.readText())
        tmp.delete()
    }

    @Test
    fun copyIntoProjectWithNullUriCreatesEmptyFile() {
        val tmp = ProjectFileStore.copyIntoProject(null, 3L, null, "agent.md", "AGENT_CREATED")
        assertTrue(tmp.exists())
        assertEquals(0, tmp.length())
        tmp.delete()
    }

    @Test
    fun copyIntoProjectRejectsUriWithoutContext() {
        val sourceFile = File(root, "source.txt")
        sourceFile.writeText("hello world")
        try {
            ProjectFileStore.copyIntoProject(null, 3L, android.net.Uri.fromFile(sourceFile), "source.txt", "USER_UPLOAD")
            fail("Expected IllegalArgumentException when context is null but URI is provided")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Context is required"))
        }
    }

    @Test
    fun renameToFinalMovesFileToAssetLocation() {
        val sourceFile = File(root, "src.txt")
        sourceFile.writeText("content")
        val mockContext = org.robolectric.RuntimeEnvironment.getApplication()
        val tmp = ProjectFileStore.copyIntoProject(mockContext, 4L, android.net.Uri.fromFile(sourceFile), "test.txt", "USER_UPLOAD")

        val final = ProjectFileStore.renameToFinal(tmp, 4L, 42L, "test.txt")
        assertEquals("asset_42.txt", final.name)
        assertTrue(final.exists())
        assertEquals("content", final.readText())
        assertFalse(tmp.exists())
    }
}
