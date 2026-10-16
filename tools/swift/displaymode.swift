// Prints the main display's current CoreGraphics mode as one JSON line (read only: never changes the mode).
// "default" is true for the user's Default scaling on the built-in panel: 1512x982 points = 3024x1964 pixels.
import CoreGraphics
let id = CGMainDisplayID()
if let m = CGDisplayCopyDisplayMode(id) {
  let isDefault = m.width == 1512 && m.height == 982 && m.pixelWidth == 3024 && m.pixelHeight == 1964
  print("{\"display\": \(id), \"width\": \(m.width), \"height\": \(m.height), \"pixelWidth\": \(m.pixelWidth), " +
        "\"pixelHeight\": \(m.pixelHeight), \"refreshHz\": \(m.refreshRate), \"ioModeID\": \(m.ioDisplayModeID), " +
        "\"builtin\": \(CGDisplayIsBuiltin(id) != 0), \"default\": \(isDefault)}")
} else {
  print("{\"display\": \(id), \"error\": \"no mode\"}")
}
