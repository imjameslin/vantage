// Prints the main display's current CoreGraphics mode on one line (read-only: no screen capture, no permission prompt):
//   display <id>: <w>x<h> pt, <pw>x<ph> px, <hz> Hz
// Benchmarks and parity runs compare only within one mode (docs/GOAL.md "Display mode"): record this line per run.
import CoreGraphics
let id = CGMainDisplayID()
if let m = CGDisplayCopyDisplayMode(id) {
  print("display \(id): \(m.width)x\(m.height) pt, \(m.pixelWidth)x\(m.pixelHeight) px, \(Int(m.refreshRate.rounded())) Hz")
} else {
  print("display \(id): mode unavailable")
}
