package com.android.systemui.dreams

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dreams.complication.ComplicationHostViewController
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel
import com.android.systemui.statusbar.BlurUtils
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class DreamOverlayAnimationsControllerTest : SysuiTestCase() {

    companion object {
        private const val DREAM_BLUR_RADIUS = 50
        private const val DREAM_IN_BLUR_ANIMATION_DURATION = 1L
        private const val DREAM_IN_COMPLICATIONS_ANIMATION_DURATION = 3L
        private const val DREAM_IN_TRANSLATION_Y_DISTANCE = 6
        private const val DREAM_IN_TRANSLATION_Y_DURATION = 7L
    }

    @Mock private lateinit var mockAnimator: AnimatorSet
    @Mock private lateinit var blurUtils: BlurUtils
    @Mock private lateinit var hostViewController: ComplicationHostViewController
    @Mock private lateinit var statusBarViewController: DreamOverlayStatusBarViewController
    @Mock private lateinit var stateController: DreamOverlayStateController
    @Mock private lateinit var configController: ConfigurationController
    @Mock private lateinit var transitionViewModel: DreamingToLockscreenTransitionViewModel
    private lateinit var controller: DreamOverlayAnimationsController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        controller =
            DreamOverlayAnimationsController(
                blurUtils,
                hostViewController,
                statusBarViewController,
                stateController,
                DREAM_BLUR_RADIUS,
                transitionViewModel,
                configController,
                DREAM_IN_BLUR_ANIMATION_DURATION,
                DREAM_IN_COMPLICATIONS_ANIMATION_DURATION,
                DREAM_IN_TRANSLATION_Y_DISTANCE,
                DREAM_IN_TRANSLATION_Y_DURATION,
            )

        val mockView: View = mock()
        whenever(mockView.resources).thenReturn(mContext.resources)

        runBlocking(Dispatchers.Main.immediate) { controller.init(mockView) }
    }

    @Test
    fun testExitAnimationUpdatesState() {
        controller.startExitAnimations(animatorBuilder = { mockAnimator })

        verify(stateController).setExitAnimationsRunning(true)

        val captor = argumentCaptor<Animator.AnimatorListener>()
        verify(mockAnimator).addListener(captor.capture())

        captor.value.onAnimationEnd(mockAnimator)
        verify(stateController).setExitAnimationsRunning(false)
    }

    @Test
    fun testWakeUpCallsExecutor() {
        val mockExecutor: DelayableExecutor = mock()
        val mockCallback: Runnable = mock()

        controller.wakeUp(
            doneCallback = mockCallback,
            executor = mockExecutor,
        )

        verify(mockExecutor).executeDelayed(eq(mockCallback), anyLong())
    }

    @Test
    fun testWakeUpAfterStartWillCancel() {
        val mockStartAnimator: AnimatorSet = mock()

        controller.startEntryAnimations(false, animatorBuilder = { mockStartAnimator })

        verify(mockStartAnimator, never()).cancel()

        controller.wakeUp(
            doneCallback = mock(),
            executor = mock(),
        )

        // Verify that we cancelled the start animator in favor of the exit
        // animator.
        verify(mockStartAnimator, times(1)).cancel()
    }

    @Test
    fun testEntryAnimations_translatesUpwards() {
        val mockStartAnimator: AnimatorSet = mock()

        controller.startEntryAnimations(
            /* downwards= */ false,
            animatorBuilder = { mockStartAnimator }
        )

        val animatorCaptor = ArgumentCaptor.forClass(Animator::class.java)
        verify(mockStartAnimator).playTogether(animatorCaptor.capture())

        // Check if there's a ValueAnimator starting at the expected Y distance.
        val animators: List<ValueAnimator> = animatorCaptor.allValues as List<ValueAnimator>
        assertTrue(
            animators.any {
                // Call setCurrentFraction so the animated value jumps to the initial value.
                it.setCurrentFraction(0f)
                it.animatedValue == DREAM_IN_TRANSLATION_Y_DISTANCE.toFloat()
            }
        )
    }

    @Test
    fun testEntryAnimations_translatesDownwards() {
        val mockStartAnimator: AnimatorSet = mock()

        controller.startEntryAnimations(
            /* downwards= */ true,
            animatorBuilder = { mockStartAnimator }
        )

        val animatorCaptor = ArgumentCaptor.forClass(Animator::class.java)
        verify(mockStartAnimator).playTogether(animatorCaptor.capture())

        // Check if there's a ValueAnimator starting at the expected Y distance.
        val animators: List<ValueAnimator> = animatorCaptor.allValues as List<ValueAnimator>
        assertTrue(
            animators.any {
                // Call setCurrentFraction so the animated value jumps to the initial value.
                it.setCurrentFraction(0f)
                it.animatedValue == -DREAM_IN_TRANSLATION_Y_DISTANCE.toFloat()
            }
        )
    }
}
