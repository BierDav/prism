package at.quickme.prism.shared

/** HostFormat says what the Machines views show. Throws on the first failure. */
fun hostFormatTests() {
    fun expect(actual: String, expected: String) = check(actual == expected) { "expected $expected, got $actual" }
    expect(HostFormat.bytes(512L), "512 B")
    expect(HostFormat.bytes(31_756_042_240L), "29.6 GB")
    expect(HostFormat.bytes(51_539_607_552L), "48 GB")
    expect(HostFormat.bytes(994_610_155_520L), "926 GB")
    expect(HostFormat.bytes(1_023_000_000_000.0), "953 GB")
    expect(HostFormat.bytes(1_099_511_627_776L), "1 TB")
    expect(HostFormat.bytes(-2048.0), "-2 KB")
    expect(HostFormat.rate(325_822.0), "318 KB/s")
    expect(HostFormat.percent(0.4237), "42%")
    expect(HostFormat.percent(1.4), "100%")
    expect(HostFormat.duration(523_286), "6 d 1 h")
    expect(HostFormat.duration(3 * 3600 + 720), "3 h 12 min")
    expect(HostFormat.duration(59), "0 min")
    expect(HostFormat.duration(86400), "1 d")
    expect(HostFormat.load(3.4249), "3.42")
    expect(HostFormat.load(0.05), "0.05")
    expect(HostFormat.load(26.13), "26.1")
    expect(HostFormat.load(104.6), "105")
    check(HostFormat.level(0.5) == 0 && HostFormat.level(0.85) == 1 && HostFormat.level(0.95) == 2) { "levels" }
}
