# UI Performance Optimization Package

This package provides utilities for optimizing UI performance on Android devices with different refresh rates (120Hz, 90Hz, 60Hz).

## Overview

### Why Optimize for Different Refresh Rates?

- **120Hz devices**: Can render 120 frames per second (8.3ms per frame)
- **90Hz devices**: Can render 90 frames per second (11.1ms per frame)
- **60Hz devices**: Can render 60 frames per second (16.7ms per frame)

Higher refresh rates provide smoother animations and better user experience, but require:
1. Shorter animation durations to maintain perceived speed
2. Efficient frame budgeting to avoid dropped frames
3. Hardware acceleration for transformations

## Components

### 1. RefreshRateManager

Manages display refresh rate detection and optimization.

```kotlin
// Get current refresh rate
val currentRate = RefreshRateManager.getCurrentRefreshRate(window)

// Get supported refresh rates
val supportedRates = RefreshRateManager.getSupportedRefreshRates(window)

// Enable highest available refresh rate
RefreshRateManager.enableHighestRefreshRate(window)

// Set specific refresh rate
RefreshRateManager.setPreferredRefreshRate(window, RefreshRateManager.RefreshRate.HIGH)
```

### 2. AnimationOptimizer

Provides optimized animation specifications.

```kotlin
// Create optimized spring animation
val springSpec = AnimationOptimizer.optimizedSpring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow
)

// Create optimized tween animation
val tweenSpec = AnimationOptimizer.optimizedTween(
    durationMs = 300,
    easing = FastOutSlowInEasing
)

// Use optimized durations
val fastDuration = AnimationOptimizer.Durations.fast()  // ~150ms
val normalDuration = AnimationOptimizer.Durations.normal()  // ~300ms
val slowDuration = AnimationOptimizer.Durations.slow()  // ~500ms
```

### 3. RefreshRateExtensions

Compose extensions for easy integration.

```kotlin
// Remember current refresh rate
val refreshRate = rememberRefreshRate()

// Enable highest refresh rate for composition duration
EnableHighestRefreshRate()

// Get optimized duration for refresh rate
val duration = animationDurationForRefreshRate(300)

// Get optimal frame duration
val frameDuration = optimalFrameDurationMs()
```

### 4. FrameRateMonitor

Monitors actual frame rates during runtime.

```kotlin
// Start monitoring
FrameRateMonitor.startMonitoring()

// Add callback for frame rate updates
FrameRateMonitor.addCallback(object : FrameRateMonitor.FrameRateCallback {
    override fun onFrameRateUpdate(fps: Float, frameTimeMs: Float) {
        // Handle frame rate update
    }
})

// Get current metrics
val fps = FrameRateMonitor.getCurrentFps()
val frameTime = FrameRateMonitor.getAverageFrameTimeMs()

// Stop monitoring
FrameRateMonitor.stopMonitoring()
```

### 5. RefreshRateDebugOverlay

Debug overlay for visualizing refresh rate information.

```kotlin
// Show debug overlay
RefreshRateDebugOverlay()

// Toggleable overlay
ToggleableRefreshRateDebugOverlay(enabled = BuildConfig.DEBUG)
```

## Migration Guide

### Step 1: Enable Hardware Acceleration

Add to `AndroidManifest.xml`:

```xml
<application
    android:hardwareAccelerated="true"
    ...>
```

### Step 2: Enable Highest Refresh Rate

Add to your main Activity or Composable:

```kotlin
@Composable
fun MyApp() {
    EnableHighestRefreshRate()
    
    // Rest of your app
}
```

### Step 3: Replace Animation Specs

#### Before (Manual Duration Scaling):

```kotlin
val infiniteTransition = rememberInfiniteTransition(label = "blink")
val alpha by infiniteTransition.animateFloat(
    initialValue = 0.3f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(
        animation = tween(700, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse
    ),
    label = "alpha"
)
```

#### After (Auto-Optimized):

```kotlin
val alpha by optimizedBlinkTransition(
    initialValue = 0.3f,
    targetValue = 1.0f,
    durationMs = 700,
    label = "blink"
)
```

### Step 4: Replace Color/Elevation Animations

#### Before:

```kotlin
val bgColor by animateColorAsState(
    targetValue = if (isActive) activeColor else Color.Transparent,
    animationSpec = tween(200),
    label = "bg"
)

val elevation by animateDpAsState(
    targetValue = if (isActive) 1.dp else 0.dp,
    animationSpec = tween(200),
    label = "elevation"
)
```

#### After:

```kotlin
val bgColor = OptimizedColorAnimation(
    isActive = isActive,
    activeColor = activeColor,
    inactiveColor = Color.Transparent
)

val elevation = OptimizedElevationAnimation(isActive = isActive)
```

## Best Practices

### 1. Use Hardware-Accelerated Transformations

```kotlin
// ✓ Good: Uses graphicsLayer (hardware-accelerated)
modifier.graphicsLayer {
    alpha = alphaValue
    scaleX = scaleValue
    scaleY = scaleValue
}

// ✗ Avoid: Uses modifier.alpha (may not be hardware-accelerated)
modifier.alpha(alphaValue)
```

### 2. Prefer Springs Over Tweens

```kotlin
// ✓ Good: More natural motion
AnimationOptimizer.optimizedSpring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium
)

// ✗ Less ideal: Fixed timing
tween(300)
```

### 3. Batch Animations

```kotlin
// ✓ Good: Single graphicsLayer block for multiple properties
modifier.graphicsLayer {
    alpha = alphaValue
    translationX = translationValue
    rotationZ = rotationValue
}

// ✗ Avoid: Multiple separate modifiers
modifier.alpha(alphaValue)
modifier.offset { IntOffset(translationValue.toInt(), 0) }
modifier.rotate(rotationValue)
```

### 4. Use Key for Stable Compositions

```kotlin
// ✓ Good: Stable key prevents unnecessary recompositions
key(chunk) {
    MarkdownText(
        markdown = chunk,
        // ...
    )
}
```

### 5. Monitor Performance in Debug Builds

```kotlin
if (BuildConfig.DEBUG) {
    RefreshRateDebugOverlay()
    
    val frameRate = rememberFrameRate()
    LaunchedEffect(frameRate) {
        if (frameRate.intValue < 55) {
            Log.w("Performance", "Low FPS: ${frameRate.intValue}")
        }
    }
}
```

## Performance Considerations

### Frame Budget

- **120Hz**: 8.3ms per frame
- **90Hz**: 11.1ms per frame
- **60Hz**: 16.7ms per frame

If a frame takes longer than the budget, it will be dropped.

### Memory Usage

- `graphicsLayer` uses hardware memory for transformations
- Avoid creating new `graphicsLayer` blocks every recomposition
- Use `remember` for stable transformation blocks

### Battery Impact

- Higher refresh rates consume more battery
- Consider reducing refresh rate when not animating
- Use `EnableHighestRefreshRate()` only when needed

## Debugging

### Common Issues

1. **Janky animations**: Check if frame rate is stable
2. **Memory leaks**: Ensure `FrameRateMonitor` is stopped when not needed
3. **Battery drain**: Monitor refresh rate usage

### Tools

- Android Studio GPU Profiler
- Layout Inspector
- Systrace
- `FrameRateMonitor` in this package

## Examples

See `AnimationExamples.kt` for complete examples of:
- Optimized MCP loading indicator
- Optimized thinking pulse animation
- Optimized color/elevation animations
- Performance monitoring dashboard
