"""Decodes what MetricsTest wrote with the reference snappy (python-snappy)
and a protobuf reader for Prometheus' WriteRequest, and checks it.

  python3 remote_write_decode.py <out.bin>
"""

import struct
import sys

import snappy


def varint(b, i):
    shift = value = 0
    while True:
        byte = b[i]
        i += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, i
        shift += 7


def fields(b):
    i = 0
    while i < len(b):
        key, i = varint(b, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            v, i = varint(b, i)
        elif wire == 1:
            v = b[i:i + 8]
            i += 8
        elif wire == 2:
            n, i = varint(b, i)
            v = b[i:i + n]
            i += n
        else:
            raise ValueError("wire type %d" % wire)
        yield field, wire, v


def decode(raw):
    out = []
    for f, w, ts in fields(raw):
        assert f == 1 and w == 2, "WriteRequest has only timeseries"
        labels, samples = [], []
        for g, w2, v in fields(ts):
            if g == 1:
                kv = dict((h, x.decode()) for h, _, x in fields(v))
                labels.append((kv[1], kv[2]))
            elif g == 2:
                s = dict((h, x) for h, _, x in fields(v))
                samples.append((struct.unpack("<d", s[1])[0], s.get(2, 0)))
        out.append((labels, samples))
    return out


def main():
    path = sys.argv[1]
    series = decode(snappy.uncompress(open(path, "rb").read()))
    assert series, "no series"
    for labels, samples in series:
        names = [k for k, _ in labels]
        assert names == sorted(names), "labels not sorted: %r" % names
        assert names[0] == "__name__", labels
        assert samples, labels
        times = [t for _, t in samples]
        assert times == sorted(times), "samples out of order: %r" % labels
    by = {tuple(l): s for l, s in series}
    level = [s for l, s in series if dict(l)["__name__"] == "phone_battery_level_ratio"]
    assert len(level) == 1 and [round(v, 2) for v, _ in level[0]] == [0.8, 0.79, 0.78], level
    assert level[0][0][1] == 1_800_000_000_000, level
    chat = [s for l, s in series if dict(l).get("package") == "com.example.chat"
            and dict(l)["__name__"] == "phone_app_estimated_charge_ampere_hours_total"]
    assert abs(chat[0][0][0] - 0.12345) < 1e-12, chat
    print("decoded %d series, %d samples" % (len(series), sum(len(s) for _, s in series)))

    big = decode(snappy.uncompress(open(path + ".big", "rb").read()))
    assert len(big) == 3000 and big[2999][1] == [(2999.0, 3999)], big[2999]
    print("decoded the multi-chunk body: %d series" % len(big))


if __name__ == "__main__":
    main()
