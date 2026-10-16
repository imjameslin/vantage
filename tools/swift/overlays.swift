import CoreGraphics
let opts = CGWindowListOption(arrayLiteral: .optionOnScreenOnly)
if let list = CGWindowListCopyWindowInfo(opts, kCGNullWindowID) as? [[String: Any]] {
  for w in list {
    let layer = w[kCGWindowLayer as String] as? Int ?? 0
    if layer > 0 && layer < 1000 {
      let owner = w[kCGWindowOwnerName as String] as? String ?? "?"
      let pid = w[kCGWindowOwnerPID as String] as? Int ?? -1
      let name = w[kCGWindowName as String] as? String ?? ""
      let b = w[kCGWindowBounds as String] as? [String: Any] ?? [:]
      print("layer \(layer) pid=\(pid) owner=\(owner) name='\(name)' bounds=\(b)")
    }
  }
}
