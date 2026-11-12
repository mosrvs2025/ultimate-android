# Workouts (Health screen)

Added in 2.4.0. Code: `health/Workouts.kt` (counting, log, store), `health/WorkoutCard.kt` (Health card, hand logging),
`vision/WorkoutCamera.kt` (camera screen, builds with body tracking only), tests in `WorkoutTest.kt`.

## How it counts

MediaPipe Pose (already used by Playground) gives 33 body points per picture, on the phone; the picture is dropped. Each exercise reduces
the body to one number, how far through the movement you are (0 = start, 1 = fully done), from the joint angles or heights that define it:

| Exercise | Signal |
|---|---|
| Squat | knee angle; or hip drop relative to leg length when the knee barely bends on screen (front view), only while the knee is bending |
| Push-up | elbow angle, and only while the body is roughly level (side-on) |
| Sit-up | angle at the hip between shoulder and knee |
| Lunge | knee angle, only with a wide stance (a squat is not a lunge) |
| Jumping jack | wrists above shoulders and feet apart, together |
| Bicep curl | elbow angle (calls out an elbow swinging forward) |
| Shoulder press | wrists from shoulder height to above the head |
| High knees | each knee reaching hip height, per leg |
| Plank | seconds with the body level and straight (shoulder-hip-ankle angle at least 160°) |

A rep counts when the number goes past a high mark and comes back under a low mark, over a minimum time (shorter for jumping jacks).
Reaching only part-way is not counted and says so. A flicker of lost tracking (under 1.2 s) does not break a rep; leaving the picture mid-rep
discards it. The depth of each counted rep is kept as a quality figure.

## Tested (simulated bodies)

Stick figures with joint jitter, moved through each exercise: full reps counted exactly (10 squats, 8 push-ups, 6 sit-ups, 5 lunges,
12 jumping jacks, 8 curls, 6 presses, 10 high-knee lifts, a 20 s plank within 0.8 s); half squats, standing still, walking towards the
phone, standing instead of a push-up position, a squat as a lunge, and a sagging plank count nothing; a third of a second of lost
tracking mid-rep still counts it once.

## Not verified

Not tried on a person. Real pose tracking is noisier and sees limbs differently than my stick figures; thresholds may need adjusting
(too strict: reps missed; too loose: false counts). Camera placement matters: the angles are measured in a flat picture. Side-on
push-ups need the phone at floor level. No calorie estimate is made (it would need your weight and a guess at intensity).

## Privacy

Sets are kept in the app's private storage on the phone. Nothing is uploaded; nothing is written to Health Connect.
