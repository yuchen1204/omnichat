package com.omnichat.ui.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProjectSessionTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var app: Application
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        app = RuntimeEnvironment.getApplication()
        viewModel = ChatViewModel(app)
    }

    @Test
    fun creatingProjectSessionDoesNotAddItToOrdinarySessions() = runTest {
        // Arrange: create a project first
        val projectId = viewModel.createProjectAndWait("TestProject", "desc")

        // Act: create a project session
        val id = viewModel.createProjectSessionAndWait(projectId, "Research")

        // Assert: the session should NOT appear in nonProjectSessions
        assertTrue(viewModel.nonProjectSessions.value.none { it.id == id })
        assertTrue(viewModel.projectSessions.value.any { it.id == id })
    }

    @Test
    fun projectPromptSkipsGlobalMemoryAndIncludesAssetIndex() = runTest {
        // Arrange: create a project and a project session
        val projectId = viewModel.createProjectAndWait("TestProject", "desc")
        val sessionId = viewModel.createProjectSessionAndWait(projectId, "Research")

        // Act: build the system prompt for this session
        val prompt = viewModel.buildPromptForSession(sessionId)

        // Assert: should contain project tool references, should NOT contain global memory
        assertTrue("Prompt should mention project_list_knowledge", prompt.contains("project_list_knowledge"))
        assertFalse("Prompt should NOT contain [CROSS_SESSION_MEMORY]", prompt.contains("[CROSS_SESSION_MEMORY]"))
    }
}
