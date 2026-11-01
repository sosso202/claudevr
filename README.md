# VRClaude

A phone VR app for Cardboard-style viewers, inspired by [CardboardXR](https://github.com/brenpoly/CardboardXR) but written from scratch without Unity: plain Kotlin, OpenGL ES, ARCore and MediaPipe.

- Split-screen stereo with lens distortion
- 6DoF head tracking, camera passthrough and floor/table detection (ARCore)
- Hand tracking from the rear camera with pinch to click, grab and throw (MediaPipe)
- Cubes mode: place, grab, throw and stack cubes on real surfaces
- Paint mode: paint strokes in the air with a pinch

## Get the APK

Every push builds the app on GitHub. Open the **Releases** page of this repo on your phone and download the newest `VRClaude.apk`. Allow your browser to install unknown apps when Android asks.

The phone needs ARCore: install **Google Play Services for AR** from the Play Store.

## Controls

| Action | How |
|---|---|
| Click | Look at a button and pinch (thumb + index) in front of the camera, or tap the screen, or press volume down |
| Grab / throw a cube | Pinch near a cube (or look at it and pinch), move, open your fingers |
| Paint | Switch to Paint mode, then pinch and move your hand |
| Phone view / viewer view | Hold a finger on the screen |
| Bring the panel back | Volume up |

The reticle turns light blue while the camera sees your hand, and green over a button.
