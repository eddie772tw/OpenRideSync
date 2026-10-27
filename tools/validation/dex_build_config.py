"""Read constants from a named BuildConfig class, not arbitrary matching DEX strings.

Bounded reader for ordinary little-endian DEX 035/037/038/039/040. It does not
execute bytecode, interpret <clinit>, or support compact/container DEX.
Format: https://source.android.com/docs/core/runtime/dex-format
"""
import hashlib
import struct
import zlib


class DexError(ValueError):
    pass


class Dex:
    def __init__(self, data):
        data = bytes(data)
        self.data = data
        if data[:8] not in {b"dex\n" + n + b"\0" for n in
                            (b"035", b"037", b"038", b"039", b"040")}:
            raise DexError("Unsupported DEX format")
        if (self.u32(32), self.u32(36), self.u32(40)) != (len(data), 112, 0x12345678):
            raise DexError("Invalid DEX header")
        if self.u32(8) != zlib.adler32(data[12:]) or data[12:32] != hashlib.sha1(data[32:]).digest():
            raise DexError("DEX checksum/signature mismatch")
        self.string_count, self.string_off = self.table(56, 4)
        self.type_count, self.type_off = self.table(64, 4)
        self.field_count, self.field_off = self.table(80, 8)
        self.class_count, self.class_off = self.table(96, 32)

    def take(self, offset, size):
        if offset < 0 or size < 0 or offset + size > len(self.data):
            raise DexError("DEX range out of bounds")
        return self.data[offset:offset + size]

    def u32(self, offset):
        return struct.unpack("<I", self.take(offset, 4))[0]

    def table(self, offset, width):
        count, start = self.u32(offset), self.u32(offset + 4)
        self.take(start, count * width)
        return count, start

    def uleb(self, offset):
        result = 0
        for shift in range(0, 35, 7):
            value = self.take(offset, 1)[0]
            offset += 1
            if shift == 28 and value & 0xF0:
                raise DexError("Oversized ULEB128")
            result |= (value & 0x7F) << shift
            if not value & 0x80:
                return result, offset
        raise DexError("Unterminated ULEB128")

    def string(self, index):
        if not 0 <= index < self.string_count:
            raise DexError("String index out of bounds")
        units, offset = self.uleb(self.u32(self.string_off + index * 4))
        end = self.data.find(b"\0", offset)
        if end < 0:
            raise DexError("Unterminated DEX string")
        # Identity fields/descriptors are ASCII. Reject ambiguous MUTF-8 instead
        # of normalizing it into a provenance value that was never in the APK.
        try:
            value = self.data[offset:end].decode("ascii")
        except UnicodeDecodeError as error:
            raise DexError("Non-ASCII identity string") from error
        if len(value) != units:
            raise DexError("DEX string length mismatch")
        return value

    def descriptor(self, index):
        if not 0 <= index < self.type_count:
            raise DexError("Type index out of bounds")
        return self.string(self.u32(self.type_off + index * 4))

    def field(self, index, owner):
        if not 0 <= index < self.field_count:
            raise DexError("Field index out of bounds")
        class_index, _, name_index = struct.unpack("<HHI", self.take(self.field_off + index * 8, 8))
        if class_index != owner:
            raise DexError("BuildConfig field owner mismatch")
        return self.string(name_index)

    def value(self, offset):
        header = self.take(offset, 1)[0]
        kind, arg = header & 31, header >> 5
        offset += 1
        if kind == 0x1F and arg <= 1:  # boolean has no payload
            return bool(arg), offset
        if kind == 0x1E and arg == 0:
            return None, offset
        if kind not in (0x04, 0x17) or arg > 3:  # int or string reference
            raise DexError("Unsupported BuildConfig encoded value")
        raw = self.take(offset, arg + 1)
        number = int.from_bytes(raw, "little", signed=kind == 0x04)
        return (self.string(number) if kind == 0x17 else number), offset + arg + 1

    def build_config(self, package):
        target = "L" + package.replace(".", "/") + "/BuildConfig;"
        matches = []
        # Avoid decoding every descriptor: Android libraries can have legal
        # non-ASCII class names unrelated to the identity class being inspected.
        target_bytes = target.encode("ascii")
        for index in range(self.class_count):
            at = self.class_off + index * 32
            owner = self.u32(at)
            if owner >= self.type_count:
                raise DexError("Class owner index out of bounds")
            string_id = self.u32(self.type_off + owner * 4)
            if string_id >= self.string_count:
                raise DexError("Descriptor index out of bounds")
            _, start = self.uleb(self.u32(self.string_off + string_id * 4))
            end = self.data.find(b"\0", start)
            if end < 0:
                raise DexError("Unterminated class descriptor")
            if self.data[start:end] != target_bytes:
                continue
            if self.descriptor(owner) != target:
                raise DexError("Class descriptor mismatch")
            fields_at, values_at = self.u32(at + 24), self.u32(at + 28)
            if not fields_at or not values_at:
                raise DexError("BuildConfig static values missing")
            counts = []
            for _ in range(4):
                count, fields_at = self.uleb(fields_at)
                counts.append(count)
            if counts[0] > self.field_count:
                raise DexError("Too many static fields")
            names, field_index = [], 0
            for _ in range(counts[0]):
                delta, fields_at = self.uleb(fields_at)
                access, fields_at = self.uleb(fields_at)
                field_index += delta
                if not access & 8:
                    raise DexError("Non-static field in static field table")
                names.append(self.field(field_index, owner))
            if len(set(names)) != len(names):
                raise DexError("Duplicate BuildConfig fields")
            count, values_at = self.uleb(values_at)
            if count > len(names):
                raise DexError("Too many static values")
            values = {}
            for name in names[:count]:
                values[name], values_at = self.value(values_at)
            matches.append(values)
        return matches
