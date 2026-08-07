package com.omnichat.ui.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.work.testing.WorkManagerTestInitHelper
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.Project
import com.omnichat.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProjectSessionTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var app: Application
    private lateinit var viewModel: ChatViewModel
    private lateinit var repository: AppRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val db = AppDatabase.getDatabase(app)
        repository = AppRepository(db)
        viewModel = ChatViewModel(app)
    }

    @Test
    fun creatingProjectSessionDoesNotAddItToOrdinarySessions() = runTest {
        // Arrange: create a project first
        val projectId = viewModel.createProjectAndWait("TestProject", "desc")

        // Act: create a project session
        val id = viewModel.createProjectSessionAndWait(projectId, "Research")

        // Assert via repository (avoids StateFlow timing issues in tests)
        val nonProject = repository.nonProjectSessions.first()
        assertTrue("Project session should not appear in non-project sessions",
            nonProject.none { it.id == id })

        val project = repository.getSessionsByProjectFlow(projectId).first()
        assertTrue("Project session should appear in project sessions",
            project.any { it.id == id })
    }

    @Test
    fun projectPromptSkipsGlobalMemoryAndIncludesAssetIndex() = runTest {
        // Arrange: create a project and a project session
        val projectId = viewModel.createProjectAndWait("TestProject", "desc")
        val sessionId = viewModel.createProjectSessionAndWait(projectId, "Research")

        // Verify the session has the correct projectId
        val session = repository.getSessionById(sessionId)
        assertNotNull("Session should exist in DB", session)
        assertEquals("Session should have projectId set", projectId, session!!.projectId)

        // Act: build the system prompt for this session
        val prompt = viewModel.buildPromptForSession(sessionId)

        // Assert: should contain project tool references, should NOT contain global memory
        assertTrue("Prompt should mention project_list_knowledge", prompt.contains("project_list_knowledge"))
        assertFalse("Prompt should NOT contain [CROSS_SESSION_MEMORY]", prompt.contains("[CROSS_SESSION_MEMORY]"))
    }
}
