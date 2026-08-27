**Comparison target**

- Source visual truth: `C:\Users\luckythrower\Documents\xwechat_files\wxid_zn12m1wj72a022_189a\temp\RWTemp\2026-08\60a1357acf4b3173a039ec4eda262ace\1d4fb6572c4b2cd5729cb73a7edc18ab.jpg`
- Source pixels: 1236 x 794
- Implementation screenshot: unavailable
- Viewport, CSS size, and density normalization: unavailable because no Android device or AVD is connected or configured
- State: spelling keyboard, lowercase letter layer

**Full-view comparison evidence**

The source image was opened at original resolution. The implementation could not be rendered or captured because `adb devices` returned no connected targets and `emulator -list-avds` returned no configured AVDs.

**Focused region comparison evidence**

Blocked for the same reason. Code inspection is not being substituted for rendered visual evidence.

**Findings**

- [P1] Rendered fidelity cannot be confirmed.
  Location: spelling keyboard.
  Evidence: the reference is available, but there is no implementation screenshot at a matching viewport.
  Impact: key sizing, text baseline, shadow softness, and narrow-screen wrapping cannot be judged visually.
  Fix: run the Debug app on a phone or AVD, open a spelling question, capture the keyboard, and compare it with the reference at a normalized scale.

**Comparison history**

- Initial pass: blocked before visual comparison because no Android render target is available.
- Hierarchy pass: removed the duplicate full-width submit control, grouped pronunciation and voice settings by priority, changed the spelling opt-out to a tertiary text action, and made the next-question action appear only after submission. Rendered comparison remains blocked because no Android target is available.
- Reversion pass: restored the original simple QWERTY button styling, removed the extra number/symbol/shift/language layers, restored a separate submit action, and changed the spelling opt-out into a clearly bordered secondary button. Rendered comparison remains blocked because no Android target is available.

**Implementation checklist**

- Connect a phone or create/start an Android AVD.
- Open the lowercase spelling-keyboard state.
- Capture the full keyboard at the device viewport.
- Compare typography, row offsets, key proportions, colors, radii, shadows, icons, and labels with the source.
- Fix any P0/P1/P2 differences and repeat the capture.

**Follow-up polish**

- None classified until rendered evidence is available.

final result: blocked
