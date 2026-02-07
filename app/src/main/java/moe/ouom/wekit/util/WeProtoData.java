package moe.ouom.wekit.util;

import static cn.hutool.core.convert.Convert.hexToBytes;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import moe.ouom.wekit.util.log.WeLogger;

public class WeProtoData {

    private static final class Field {
        final int fieldNumber;
        final int wireType;
        Object value;

        Field(int fieldNumber, int wireType, Object value) {
            this.fieldNumber = fieldNumber;
            this.wireType = wireType;
            this.value = value;
        }
    }

    private enum LenView {
        AUTO, SUB, UTF8, HEX
    }

    private static final class LenValue {
        byte[] raw;
        String utf8;
        WeProtoData subMessage;
        LenView view;

        LenValue(byte[] raw) {
            this.raw = raw != null ? raw : new byte[0];
            this.view = LenView.AUTO;
        }
    }

    private final List<Field> fields = new ArrayList<>();
    private byte[] packetPrefix = new byte[0];

    public static boolean hasPacketPrefix(byte[] b) {
        return b != null && b.length >= 4 && (b[0] & 0xFF) == 0;
    }

    public static byte[] getUnpPackage(byte[] b) {
        if (b == null) return null;
        if (b.length < 4) return b;
        if ((b[0] & 0xFF) == 0) return Arrays.copyOfRange(b, 4, b.length);
        return b;
    }

    public void clear() {
        fields.clear();
        packetPrefix = new byte[0];
    }

    public byte[] getPacketPrefix() {
        return packetPrefix != null ? Arrays.copyOf(packetPrefix, packetPrefix.length) : new byte[0];
    }

    public void setPacketPrefix(byte[] prefix) {
        this.packetPrefix = prefix != null ? Arrays.copyOf(prefix, prefix.length) : new byte[0];
    }

    public void fromBytes(byte[] b) throws IOException {
        clear();
        if (b == null) return;

        byte[] body = b;
        if (hasPacketPrefix(b)) {
            packetPrefix = Arrays.copyOfRange(b, 0, 4);
            body = Arrays.copyOfRange(b, 4, b.length);
        }
        parseMessageBytes(body, true);
    }

    public void fromMessageBytes(byte[] b) throws IOException {
        clear();
        packetPrefix = new byte[0];
        parseMessageBytes(b, true);
    }

    private void parseMessageBytes(byte[] b, boolean analyzeLen) throws IOException {
        if (b == null) return;

        CodedInputStream in = CodedInputStream.newInstance(b);
        while (!in.isAtEnd()) {
            final int tag;
            try {
                tag = in.readTag();
            } catch (InvalidProtocolBufferException e) {
                throw new InvalidProtocolBufferException(e);
            }

            if (tag == 0) break;

            int fieldNumber = tag >>> 3;
            int wireType = tag & 7;

            if (wireType == 4 || wireType == 3 || wireType > 5) {
                throw new IOException("Unexpected wireType: " + wireType);
            }

            switch (wireType) {
                case 0: {
                    long v = in.readInt64();
                    fields.add(new Field(fieldNumber, wireType, v));
                    break;
                }
                case 1: {
                    long v = in.readFixed64();
                    fields.add(new Field(fieldNumber, wireType, v));
                    break;
                }
                case 2: {
                    byte[] subBytes = in.readByteArray();
                    LenValue lv = new LenValue(subBytes);
                    if (analyzeLen) analyzeLenValue(lv);
                    fields.add(new Field(fieldNumber, wireType, lv));
                    break;
                }
                case 5: {
                    int v = in.readFixed32();
                    fields.add(new Field(fieldNumber, wireType, v));
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static void analyzeLenValue(LenValue lv) {
        if (lv == null) return;

        WeProtoData sub = tryParseSubMessageStrong(lv.raw);
        if (sub != null) {
            lv.subMessage = sub;
            lv.utf8 = null;
            lv.view = LenView.SUB;
            return;
        }

        String s = tryDecodeUtf8Roundtrip(lv.raw);
        if (s != null) {
            lv.utf8 = s;
            lv.subMessage = null;
            lv.view = LenView.UTF8;
            return;
        }

        lv.utf8 = null;
        lv.subMessage = null;
        lv.view = LenView.HEX;
    }

    private static String tryDecodeUtf8Roundtrip(byte[] b) {
        try {
            String s = new String(b, StandardCharsets.UTF_8);
            byte[] re = s.getBytes(StandardCharsets.UTF_8);
            if (Arrays.equals(b, re)) return s;
        } catch (Exception ignored) { }
        return null;
    }

    private static WeProtoData tryParseSubMessageStrong(byte[] b) {
        try {
            if (b == null || b.length == 0) return null;
            WeProtoData sub = new WeProtoData();
            sub.parseMessageBytes(b, true);
            if (sub.fields.isEmpty()) return null;
            byte[] re = sub.toMessageBytes();
            if (!Arrays.equals(b, re)) return null;
            return sub;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static WeProtoData ensureSubParsedStrong(LenValue lv) {
        if (lv == null) return null;
        if (lv.subMessage != null) return lv.subMessage;
        WeProtoData sub = tryParseSubMessageStrong(lv.raw);
        if (sub != null) lv.subMessage = sub;
        return lv.subMessage;
    }

    private static String ensureUtf8Decoded(LenValue lv) {
        if (lv == null) return null;
        if (lv.utf8 != null) return lv.utf8;
        String s = tryDecodeUtf8Roundtrip(lv.raw);
        if (s != null) lv.utf8 = s;
        return lv.utf8;
    }

    public JSONObject toJSON() throws Exception {
        JSONObject obj = new JSONObject();
        for (Field f : fields) {
            String k = String.valueOf(f.fieldNumber);
            Object jsonVal = fieldValueToJsonValue(f);

            if (!obj.has(k)) {
                obj.put(k, jsonVal);
            } else {
                Object existing = obj.get(k);
                JSONArray arr;
                if (existing instanceof JSONArray) {
                    arr = (JSONArray) existing;
                } else {
                    arr = new JSONArray();
                    arr.put(existing);
                    obj.put(k, arr);
                }
                arr.put(jsonVal);
            }
        }
        return obj;
    }

    private Object fieldValueToJsonValue(Field f) throws Exception {
        if (f.wireType != 2) return f.value;

        LenValue lv = (LenValue) f.value;

        LenView v = lv.view;
        if (v == LenView.AUTO) {
            WeProtoData sub = ensureSubParsedStrong(lv);
            if (sub != null) {
                lv.view = LenView.SUB;
                return sub.toJSON();
            }
            String s = ensureUtf8Decoded(lv);
            if (s != null) {
                lv.view = LenView.UTF8;
                return s;
            }
            lv.view = LenView.HEX;
            return "hex->" + bytesToHex(lv.raw);
        }

        if (v == LenView.SUB) {
            WeProtoData sub = ensureSubParsedStrong(lv);
            if (sub != null) return sub.toJSON();
            String s = ensureUtf8Decoded(lv);
            if (s != null) return s;
            return "hex->" + bytesToHex(lv.raw);
        }

        if (v == LenView.UTF8) {
            String s = ensureUtf8Decoded(lv);
            if (s != null) return s;
            WeProtoData sub = ensureSubParsedStrong(lv);
            if (sub != null) return sub.toJSON();
            return "hex->" + bytesToHex(lv.raw);
        }

        return "hex->" + bytesToHex(lv.raw);
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }

    public byte[] toBytes() {
        return toMessageBytes();
    }

    public byte[] toMessageBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bos);
        try {
            for (Field f : fields) {
                switch (f.wireType) {
                    case 0: {
                        long v = (Long) f.value;
                        if (v >= 0) out.writeUInt64(f.fieldNumber, v);
                        else out.writeInt64(f.fieldNumber, v);
                        break;
                    }
                    case 1: {
                        long v = (Long) f.value;
                        out.writeFixed64(f.fieldNumber, v);
                        break;
                    }
                    case 2: {
                        LenValue lv = (LenValue) f.value;
                        if (lv.subMessage != null) {
                            byte[] newRaw = lv.subMessage.toMessageBytes();
                            if (!Arrays.equals(newRaw, lv.raw)) lv.raw = newRaw;
                        } else if (lv.utf8 != null && lv.view == LenView.UTF8) {
                            byte[] newRaw = lv.utf8.getBytes(StandardCharsets.UTF_8);
                            if (!Arrays.equals(newRaw, lv.raw)) lv.raw = newRaw;
                        }
                        out.writeByteArray(f.fieldNumber, lv.raw != null ? lv.raw : new byte[0]);
                        break;
                    }
                    case 5: {
                        int v = (Integer) f.value;
                        out.writeFixed32(f.fieldNumber, v);
                        break;
                    }
                    default:
                        break;
                }
            }
            out.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            WeLogger.e("WeProtoData - toBytes", e);
            return new byte[0];
        }
    }

    public byte[] toPacketBytes() {
        byte[] body = toMessageBytes();
        if (packetPrefix == null || packetPrefix.length == 0) return body;
        byte[] out = new byte[packetPrefix.length + body.length];
        System.arraycopy(packetPrefix, 0, out, 0, packetPrefix.length);
        System.arraycopy(body, 0, out, packetPrefix.length, body.length);
        return out;
    }

    private int findFieldIndex(int fieldNumber, int occurrenceIndex) {
        int occ = 0;
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            if (f.fieldNumber == fieldNumber) {
                if (occ == occurrenceIndex) return i;
                occ++;
            }
        }
        return -1;
    }

    public boolean setVarint(int fieldNumber, int occurrenceIndex, long value) {
        int idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        fields.get(idx).value = value;
        return true;
    }

    public boolean setFixed64(int fieldNumber, int occurrenceIndex, long value) {
        int idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        fields.get(idx).value = value;
        return true;
    }

    public boolean setFixed32(int fieldNumber, int occurrenceIndex, int value) {
        int idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        fields.get(idx).value = value;
        return true;
    }

    public boolean setLenHex(int fieldNumber, int occurrenceIndex, String hex) {
        int idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        Field f = fields.get(idx);
        if (f.wireType != 2) return false;
        LenValue lv = (LenValue) f.value;

        String h = stripNonHex(hex);
        lv.raw = h.isEmpty() ? new byte[0] : hexToBytes(h);
        lv.utf8 = null;
        lv.subMessage = null;
        lv.view = LenView.HEX;
        return true;
    }

    public boolean setLenUtf8(int fieldNumber, int occurrenceIndex, String text) {
        int idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        Field f = fields.get(idx);
        if (f.wireType != 2) return false;
        LenValue lv = (LenValue) f.value;

        if (text == null) text = "";
        lv.utf8 = text;
        lv.raw = text.getBytes(StandardCharsets.UTF_8);
        lv.subMessage = null;
        lv.view = LenView.UTF8;
        return true;
    }

    public boolean setLenSubBytes(int fieldNumber, int occurrenceIndex, byte[] subBytes) {
        int idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        Field f = fields.get(idx);
        if (f.wireType != 2) return false;
        LenValue lv = (LenValue) f.value;

        WeProtoData sub = tryParseSubMessageStrong(subBytes);
        lv.raw = subBytes != null ? subBytes : new byte[0];
        lv.subMessage = sub;
        lv.utf8 = null;
        lv.view = sub != null ? LenView.SUB : LenView.HEX;
        return true;
    }

    public boolean removeField(int fieldNumber, int occurrenceIndex) {
        int idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        fields.remove(idx);
        return true;
    }

    public int replaceUtf8Contains(String needle, String replacement) {
        if (needle == null || needle.isEmpty()) return 0;
        if (replacement == null) replacement = "";
        return replaceUtf8ContainsInternal(needle, replacement);
    }

    private int replaceUtf8ContainsInternal(String needle, String replacement) {
        int changed = 0;
        for (Field f : fields) {
            if (f.wireType != 2) continue;
            LenValue lv = (LenValue) f.value;

            WeProtoData sub = ensureSubParsedStrong(lv);
            if (sub != null) {
                int subChanged = sub.replaceUtf8ContainsInternal(needle, replacement);
                if (subChanged > 0) {
                    lv.subMessage = sub;
                    lv.raw = sub.toMessageBytes();
                    lv.utf8 = null;
                    lv.view = LenView.SUB;
                    changed += subChanged;
                }
            }

            String s = ensureUtf8Decoded(lv);
            if (s != null && s.contains(needle)) {
                String ns = s.replace(needle, replacement);
                if (!ns.equals(s)) {
                    lv.utf8 = ns;
                    lv.raw = ns.getBytes(StandardCharsets.UTF_8);
                    lv.subMessage = null;
                    lv.view = LenView.UTF8;
                    changed++;
                }
            }
        }
        return changed;
    }

    public int replaceUtf8Regex(Pattern pattern, String replacement) {
        if (pattern == null) return 0;
        if (replacement == null) replacement = "";
        return replaceUtf8RegexInternal(pattern, replacement);
    }

    private int replaceUtf8RegexInternal(Pattern pattern, String replacement) {
        int matchesTotal = 0;
        for (Field f : fields) {
            if (f.wireType != 2) continue;
            LenValue lv = (LenValue) f.value;

            WeProtoData sub = ensureSubParsedStrong(lv);
            if (sub != null) {
                int subMatches = sub.replaceUtf8RegexInternal(pattern, replacement);
                if (subMatches > 0) {
                    lv.subMessage = sub;
                    lv.raw = sub.toMessageBytes();
                    lv.utf8 = null;
                    lv.view = LenView.SUB;
                    matchesTotal += subMatches;
                }
            }

            String s = ensureUtf8Decoded(lv);
            if (s != null) {
                Matcher m = pattern.matcher(s);
                int cnt = 0;
                while (m.find()) cnt++;
                if (cnt > 0) {
                    String ns = pattern.matcher(s).replaceAll(replacement);
                    lv.utf8 = ns;
                    lv.raw = ns.getBytes(StandardCharsets.UTF_8);
                    lv.subMessage = null;
                    lv.view = LenView.UTF8;
                    matchesTotal += cnt;
                }
            }
        }
        return matchesTotal;
    }

    private static String stripNonHex(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') ||
                    (c >= 'a' && c <= 'f') ||
                    (c >= 'A' && c <= 'F')) out.append(c);
        }
        return out.toString();
    }

    public void fromJSON(JSONObject json) {
        try {
            clear();
            Iterator<String> keyIt = json.keys();
            while (keyIt.hasNext()) {
                String key = keyIt.next();
                int fieldNumber = Integer.parseInt(key);
                Object value = json.get(key);

                if (value instanceof JSONObject) {
                    WeProtoData sub = new WeProtoData();
                    sub.fromJSON((JSONObject) value);
                    LenValue lv = new LenValue(sub.toMessageBytes());
                    lv.subMessage = sub;
                    lv.view = LenView.SUB;
                    fields.add(new Field(fieldNumber, 2, lv));
                } else if (value instanceof JSONArray) {
                    JSONArray arr = (JSONArray) value;
                    for (int i = 0; i < arr.length(); i++) {
                        Object v = arr.get(i);
                        addJsonValueAsField(fieldNumber, v);
                    }
                } else {
                    addJsonValueAsField(fieldNumber, value);
                }
            }
        } catch (Exception ignored) { }
    }

    private void addJsonValueAsField(int fieldNumber, Object value) {
        try {
            if (value instanceof JSONObject) {
                WeProtoData sub = new WeProtoData();
                sub.fromJSON((JSONObject) value);
                LenValue lv = new LenValue(sub.toMessageBytes());
                lv.subMessage = sub;
                lv.view = LenView.SUB;
                fields.add(new Field(fieldNumber, 2, lv));
            } else if (value instanceof Number) {
                long v = ((Number) value).longValue();
                fields.add(new Field(fieldNumber, 0, v));
            } else if (value instanceof String) {
                String s = (String) value;
                if (s.startsWith("hex->")) {
                    byte[] raw = hexToBytes(stripNonHex(s.substring(5)));
                    LenValue lv = new LenValue(raw);
                    lv.view = LenView.HEX;
                    fields.add(new Field(fieldNumber, 2, lv));
                } else {
                    byte[] raw = s.getBytes(StandardCharsets.UTF_8);
                    LenValue lv = new LenValue(raw);
                    lv.utf8 = s;
                    lv.view = LenView.UTF8;
                    fields.add(new Field(fieldNumber, 2, lv));
                }
            } else if (value == null) { } else {
                WeLogger.w("WeProtoData.fromJSON Unknown type: " + value.getClass().getName());
            }
        } catch (Exception ignored) { }
    }

    public int applyViewJSON(JSONObject view, boolean deleteMissing) {
        if (view == null) return 0;

        int changes = 0;

        if (deleteMissing) {
            List<Integer> existingNums = new ArrayList<>();
            for (Field f : fields) existingNums.add(f.fieldNumber);

            for (int i = 0; i < existingNums.size(); i++) {
                int fn = existingNums.get(i);
                if (!view.has(String.valueOf(fn))) {
                    changes += removeAllOccurrences(fn);
                }
            }
        }

        Iterator<String> it = view.keys();
        while (it.hasNext()) {
            String key = it.next();
            int fn;
            try {
                fn = Integer.parseInt(key);
            } catch (Exception e) {
                continue;
            }

            Object val = view.opt(key);
            if (val == null || val == JSONObject.NULL) {
                if (deleteMissing) changes += removeAllOccurrences(fn);
                continue;
            }

            if (val instanceof JSONArray) {
                JSONArray arr = (JSONArray) val;
                List<Integer> idxs = indicesOf(fn);

                int min = Math.min(arr.length(), idxs.size());
                for (int i = 0; i < min; i++) {
                    Object v = arr.opt(i);
                    if (v == JSONObject.NULL) continue;
                    changes += applyOne(fields.get(idxs.get(i)), v, deleteMissing);
                }

                if (deleteMissing && idxs.size() > arr.length()) {
                    for (int i = idxs.size() - 1; i >= arr.length(); i--) {
                        fields.remove((int) idxs.get(i));
                        changes++;
                    }
                }
            } else {
                int idx = findFieldIndex(fn, 0);
                if (idx >= 0) {
                    changes += applyOne(fields.get(idx), val, deleteMissing);
                }
            }
        }

        return changes;
    }

    private int applyOne(Field f, Object val, boolean deleteMissing) {
        if (f == null || val == null || val == JSONObject.NULL) return 0;

        try {
            switch (f.wireType) {
                case 0, 1: {
                    if (val instanceof Number) {
                        f.value = ((Number) val).longValue();
                        return 1;
                    }
                    if (val instanceof String) {
                        f.value = Long.parseLong((String) val);
                        return 1;
                    }
                    return 0;
                }
                case 5: {
                    if (val instanceof Number) {
                        f.value = ((Number) val).intValue();
                        return 1;
                    }
                    if (val instanceof String) {
                        f.value = Integer.parseInt((String) val);
                        return 1;
                    }
                    return 0;
                }
                case 2: {
                    LenValue lv = (LenValue) f.value;

                    if (val instanceof JSONObject) {
                        WeProtoData sub = ensureSubParsedStrong(lv);
                        if (sub == null) {
                            sub = new WeProtoData();
                        }
                        int c = sub.applyViewJSON((JSONObject) val, deleteMissing);
                        lv.subMessage = sub;
                        lv.raw = sub.toMessageBytes();
                        lv.utf8 = null;
                        lv.view = LenView.SUB;
                        return Math.max(1, c);
                    }

                    if (val instanceof String) {
                        String s = (String) val;
                        if (s.startsWith("hex->")) {
                            byte[] raw = hexToBytes(stripNonHex(s.substring(5)));
                            lv.raw = raw != null ? raw : new byte[0];
                            lv.utf8 = null;
                            lv.subMessage = null;
                            lv.view = LenView.HEX;
                        } else {
                            lv.utf8 = s;
                            lv.raw = s.getBytes(StandardCharsets.UTF_8);
                            lv.subMessage = null;
                            lv.view = LenView.UTF8;
                        }
                        return 1;
                    }

                    return 0;
                }
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Integer> indicesOf(int fieldNumber) {
        List<Integer> idxs = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).fieldNumber == fieldNumber) idxs.add(i);
        }
        return idxs;
    }

    private int removeAllOccurrences(int fieldNumber) {
        int removed = 0;
        for (int i = fields.size() - 1; i >= 0; i--) {
            if (fields.get(i).fieldNumber == fieldNumber) {
                fields.remove(i);
                removed++;
            }
        }
        return removed;
    }

}
