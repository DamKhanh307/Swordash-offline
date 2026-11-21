package sd;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import java.util.zip.Deflater;

public class OfflineServer {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static Context appCtx;
    private static File saveFile;
    private static JSONObject state;
    private static Map<String, List<String>> stageRooms = new HashMap<String, List<String>>();
    private static Map<Integer, List<byte[]>> protoReplay = new HashMap<Integer, List<byte[]>>();
    private static byte[] loginBin;
    private static String roleTemplate;

    public static void start(Context ctx) {
        if (ctx == null || !STARTED.compareAndSet(false, true)) return;
        appCtx = ctx.getApplicationContext();
        saveFile = new File(appCtx.getFilesDir(), "save_offline.json");
        new Thread(new Runnable() {
            public void run() {
                try {
                    loadSave();
                    loginBin = readAssetBytes("sd_offline/login_s2c_unpacked.bin");
                    roleTemplate = new String(readAssetBytes("sd_offline/role_data.txt"), "UTF-8");
                    loadStageRooms();
                    loadProtoReplay();
                    startHttpServer();
                    startTcpServer();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }).start();
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Throwable ignored) {}
        }
    }

    private static void closeSocket(Socket s) {
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static byte[] readAssetBytes(String path) throws IOException {
        InputStream is = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            is = appCtx.getAssets().open(path);
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        } finally {
            closeQuietly(is);
        }
    }

    private static synchronized void loadSave() {
        FileInputStream fis = null;
        try {
            if (saveFile.exists()) {
                fis = new FileInputStream(saveFile);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int r;
                while ((r = fis.read(buf)) != -1) bos.write(buf, 0, r);
                state = new JSONObject(bos.toString("UTF-8"));
                return;
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(fis);
        }
        state = new JSONObject();
        try {
            state.put("unlocked_stage", 1);
            state.put("coin", 5000);
            state.put("capsule", 10);
            state.put("stone", 10);
            state.put("skin", 1);
        } catch (Throwable ignored) {}
    }

    private static synchronized void writeSave() {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(saveFile);
            fos.write(state.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(fos);
        }
    }

    private static String packStars(int unlocked) {
        int[] arr = new int[]{0, 0, 0, 0, 0};
        for (int st = 1; st < unlocked; st++) {
            int idx = (st - 1) / 15;
            int shift = ((st - 1) % 15) * 2;
            if (idx < arr.length) arr[idx] |= (3 << shift);
        }
        return arr[0] + "," + arr[1] + "," + arr[2] + "," + arr[3] + "," + arr[4];
    }

    private static void loadStageRooms() throws IOException {
        String txt = new String(readAssetBytes("sd_offline/stage.txt"), "UTF-8");
        String[] lines = txt.split("\r?\n");
        if (lines.length < 5) return;
        String[] headers = lines[1].split("\t");
        int rlIdx = 8;
        for (int i = 0; i < headers.length; i++) {
            if ("roomList".equals(headers[i])) { rlIdx = i; break; }
        }
        for (int i = 4; i < lines.length; i++) {
            String[] cols = lines[i].split("\t");
            if (cols.length > rlIdx && cols[0].length() > 0 && cols[rlIdx].length() > 0) {
                List<String> list = new ArrayList<String>();
                String[] parts = cols[rlIdx].split(";");
                for (int j = 0; j < parts.length; j++) {
                    String r = parts[j].trim();
                    if (r.length() > 0) list.add(r);
                }
                stageRooms.put(cols[0], list);
            }
        }
    }

    private static byte[] sprotoPack(byte[] data) {
        int pad = (8 - (data.length % 8)) % 8;
        byte[] src = pad == 0 ? data : Arrays.copyOf(data, data.length + pad);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < src.length; i += 8) {
            int hdr = 0;
            byte[] nz = new byte[8];
            int nzc = 0;
            for (int b = 0; b < 8; b++) {
                int v = src[i + b] & 0xFF;
                if (v != 0) {
                    hdr |= (1 << b);
                    nz[nzc++] = (byte) v;
                }
            }
            out.write(hdr);
            if (hdr == 0xFF) out.write(0);
            out.write(nz, 0, nzc);
        }
        return out.toByteArray();
    }

    private static byte[] sprotoUnpack(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0, n = data.length;
        while (i < n) {
            int hdr = data[i++] & 0xFF;
            if (hdr == 0xFF) {
                if (i >= n) break;
                int cnt = ((data[i++] & 0xFF) + 1) * 8;
                int end = Math.min(n, i + cnt);
                out.write(data, i, end - i);
                i = end;
            } else {
                for (int bit = 0; bit < 8; bit++) {
                    if (((hdr >> bit) & 1) == 1) {
                        if (i < n) out.write(data[i++] & 0xFF);
                    } else {
                        out.write(0);
                    }
                }
            }
        }
        return out.toByteArray();
    }

    private static byte[] deflateRaw(String text) {
        byte[] input;
        try {
            input = text.getBytes("UTF-8");
        } catch (Throwable t) {
            input = text.getBytes();
        }
        Deflater def = new Deflater(6, true);
        def.setInput(input);
        def.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!def.finished()) {
            int c = def.deflate(buf);
            if (c > 0) bos.write(buf, 0, c);
        }
        def.end();
        return bos.toByteArray();
    }

    private static byte[] framePkt(byte[] unpacked) {
        byte[] packed = sprotoPack(unpacked);
        byte[] res = new byte[2 + packed.length];
        res[0] = (byte) ((packed.length >> 8) & 0xFF);
        res[1] = (byte) (packed.length & 0xFF);
        System.arraycopy(packed, 0, res, 2, packed.length);
        return res;
    }

    private static byte[] leInt(int v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF), (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)};
    }

    private static int readU16LE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static byte[] buildSyncResponse(byte[] sess, String luaSync) {
        byte[] comp = deflateRaw(luaSync);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(new byte[]{2, 0, 1, 0}, 0, 4);
        bos.write(sess, 0, 2);
        bos.write(new byte[]{2, 0, 4, 0, 0, 0}, 0, 6);
        bos.write(leInt(comp.length), 0, 4);
        bos.write(comp, 0, comp.length);
        return framePkt(bos.toByteArray());
    }

    private static byte[] buildLoginResponse(byte[] sess) {
        byte[] raw = Arrays.copyOf(loginBin, loginBin.length);
        raw[4] = sess[0];
        raw[5] = sess[1];
        int unlocked = state.optInt("unlocked_stage", 1);
        int skinIdx = state.optInt("skin", 1);
        String starsStr = packStars(unlocked);
        String roleTxt = roleTemplate.replaceFirst("(\\{\\{\\{0\\}\\},1,)\\d+,\\{[0-9,]+\\}", "$1" + unlocked + ",{" + starsStr + "}");
        roleTxt = roleTxt.replaceFirst(",\"\",0,\\d+\\}", ",\"\",0," + skinIdx + "}");
        byte[] newComp = deflateRaw(roleTxt);
        int pos = indexOf(raw, new byte[]{(byte) 0x9F, 0x02, 0x00, 0x00});
        if (pos != -1) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(raw, 0, pos);
            bos.write(leInt(newComp.length), 0, 4);
            bos.write(newComp, 0, newComp.length);
            int tailStart = pos + 4 + 671;
            if (tailStart < raw.length) bos.write(raw, tailStart, raw.length - tailStart);
            raw = bos.toByteArray();
        }
        return framePkt(raw);
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] d = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            d[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return d;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static void loadProtoReplay() throws IOException {
        String txt = new String(readAssetBytes("sd_offline/login_capture.log"), "UTF-8");
        byte[] c2sBuf = new byte[0], s2cBuf = new byte[0];
        Map<Integer, Integer> sessToProto = new HashMap<Integer, Integer>();
        String[] lines = txt.split("\r?\n");
        for (int k = 0; k < lines.length; k++) {
            String line = lines[k];
            int bar = line.indexOf('|');
            if (bar < 0) continue;
            String tag = line.substring(0, bar).trim();
            byte[] raw = hexToBytes(line.substring(bar + 1).trim());
            if ("C2S".equals(tag)) {
                c2sBuf = concat(c2sBuf, raw);
                int i = 0;
                while (i + 2 <= c2sBuf.length) {
                    int ln = ((c2sBuf[i] & 0xFF) << 8) | (c2sBuf[i + 1] & 0xFF);
                    if (i + 2 + ln > c2sBuf.length) break;
                    byte[] u = sprotoUnpack(Arrays.copyOfRange(c2sBuf, i + 2, i + 2 + ln));
                    if (u.length >= 6) {
                        int pt = (readU16LE(u, 2) / 2) - 1;
                        sessToProto.put(readU16LE(u, 4), pt);
                    }
                    i += 2 + ln;
                }
                c2sBuf = Arrays.copyOfRange(c2sBuf, i, c2sBuf.length);
            } else if ("S2C".equals(tag)) {
                s2cBuf = concat(s2cBuf, raw);
                int i = 0;
                while (i + 2 <= s2cBuf.length) {
                    int ln = ((s2cBuf[i] & 0xFF) << 8) | (s2cBuf[i + 1] & 0xFF);
                    if (i + 2 + ln > s2cBuf.length) break;
                    byte[] u = sprotoUnpack(Arrays.copyOfRange(s2cBuf, i + 2, i + 2 + ln));
                    if (u.length >= 6) {
                        Integer pt = sessToProto.get(readU16LE(u, 4));
                        if (pt != null) {
                            List<byte[]> list = protoReplay.get(pt);
                            if (list == null) { list = new ArrayList<byte[]>(); protoReplay.put(pt, list); }
                            list.add(u);
                        }
                    }
                    i += 2 + ln;
                }
                s2cBuf = Arrays.copyOfRange(s2cBuf, i, s2cBuf.length);
            }
        }
    }

    private static void startHttpServer() {
        new Thread(new Runnable() {
            public void run() {
                ServerSocket srv = null;
                try {
                    srv = new ServerSocket(12034, 50, InetAddress.getByName("127.0.0.1"));
                    while (true) {
                        final Socket s = srv.accept();
                        new Thread(new Runnable() {
                            public void run() { handleHttp(s); }
                        }).start();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (srv != null) { try { srv.close(); } catch (Throwable ignored) {} }
                }
            }
        }).start();
    }

    private static void handleHttp(Socket s) {
        BufferedReader br = null;
        OutputStream os = null;
        try {
            br = new BufferedReader(new InputStreamReader(s.getInputStream()));
            os = s.getOutputStream();
            String reqLine = br.readLine();
            if (reqLine == null) return;
            String[] parts = reqLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String line;
            while ((line = br.readLine()) != null && line.length() > 0) {}
            byte[] resp = path.startsWith("/route")
                    ? "{\"url\":\"127.0.0.1|2345\"}".getBytes("UTF-8")
                    : "{\"code\":0,\"data\":[]}".getBytes("UTF-8");
            String hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                    + resp.length + "\r\nConnection: close\r\n\r\n";
            os.write(hdr.getBytes("UTF-8"));
            os.write(resp);
            os.flush();
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(br);
            closeQuietly(os);
            closeSocket(s);
        }
    }

    private static void startTcpServer() {
        new Thread(new Runnable() {
            public void run() {
                ServerSocket srv = null;
                try {
                    srv = new ServerSocket(2345, 50, InetAddress.getByName("127.0.0.1"));
                    while (true) {
                        final Socket s = srv.accept();
                        new Thread(new Runnable() {
                            public void run() { handleClient(s); }
                        }).start();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (srv != null) { try { srv.close(); } catch (Throwable ignored) {} }
                }
            }
        }).start();
    }

    private static byte[] readExact(InputStream is, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = is.read(buf, off, n - off);
            if (r < 0) return null;
            off += r;
        }
        return buf;
    }

    private static int findRegexInt(byte[] data, String regex, int def) {
        try {
            Matcher m = Pattern.compile(regex).matcher(new String(data, "ISO-8859-1"));
            return m.find() ? Integer.parseInt(m.group(1)) : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static void handleClient(Socket conn) {
        Map<Integer, Integer> seen = new HashMap<Integer, Integer>();
        int currentStage = 1, currentRoom = 1;
        InputStream is = null;
        OutputStream os = null;
        try {
            is = conn.getInputStream();
            os = conn.getOutputStream();
            while (true) {
                byte[] hdr = readExact(is, 2);
                if (hdr == null) break;
                int ln = ((hdr[0] & 0xFF) << 8) | (hdr[1] & 0xFF);
                byte[] body = readExact(is, ln);
                if (body == null) break;

                byte[] u = sprotoUnpack(body);
                int pt = u.length >= 6 ? (readU16LE(u, 2) / 2) - 1 : -1;
                byte[] sess = u.length >= 6 ? new byte[]{u[4], u[5]} : new byte[]{2, 0};

                if (pt == 3) {
                    os.write(buildLoginResponse(sess));
                } else if (pt == 6) {
                    int now = (int) (System.currentTimeMillis() / 1000L);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    bos.write(new byte[]{2, 0, 1, 0}, 0, 4);
                    bos.write(sess, 0, 2);
                    bos.write(new byte[]{1, 0, 0, 0, 4, 0, 0, 0}, 0, 8);
                    bos.write(leInt(now), 0, 4);
                    os.write(framePkt(bos.toByteArray()));
                } else if (pt == 201) {
                    currentStage = findRegexInt(u, "sm_(\\d+)", currentStage);
                    currentRoom = 1;
                    long now = System.currentTimeMillis() / 1000L;
                    String stageStr = "sm_" + currentStage;
                    List<String> rl = stageRooms.get(stageStr);
                    String mapStr = (rl != null && !rl.isEmpty()) ? rl.get(0) : ("m" + currentStage + "_1");
                    String sync = "{[8]={{_={},},\"" + mapStr + "\",1,\"" + stageStr + "\"," + now + ",{_={tm=0,dt=0,ce=0,ctl={},},}},[24]={[3]=" + now + ",},}";
                    os.write(buildSyncResponse(sess, sync));
                } else if (pt == 207) {
                    String stageStr = "sm_" + currentStage;
                    List<String> rl = stageRooms.get(stageStr);
                    int maxRooms = (rl != null && !rl.isEmpty()) ? rl.size() : 10;
                    if (currentRoom < maxRooms) {
                        currentRoom++;
                        String nextMap = (rl != null && !rl.isEmpty()) ? rl.get(currentRoom - 1) : ("m" + currentStage + "_" + currentRoom);
                        String sync = "{[8]={[3]=" + currentRoom + ",[2]=\"" + nextMap + "\",},[7]={[2]=" + (currentRoom - 1) + ",},}";
                        os.write(buildSyncResponse(sess, sync));
                    } else {
                        os.write(framePkt(new byte[]{2, 0, 1, 0, sess[0], sess[1], 1, 0, 4, 0}));
                    }
                } else if (pt == 128) {
                    int skinIdx = findRegexInt(u, "skin_(\\d+)", -1);
                    if (skinIdx < 0) skinIdx = u.length >= 10 ? (readU16LE(u, 8) / 2) - 1 : 1;
                    try { state.put("skin", skinIdx); } catch (Throwable ignored) {}
                    writeSave();
                    os.write(buildSyncResponse(sess, "{[3]={[18]=" + skinIdx + ",},}"));
                } else if (pt == 202) {
                    int cleared = findRegexInt(u, "sm_(\\d+)", currentStage);
                    int unlocked = state.optInt("unlocked_stage", 1);
                    if (cleared >= unlocked) unlocked = cleared + 1;
                    int coin = state.optInt("coin", 5000) + 3000;
                    int cap = state.optInt("capsule", 10) + 5;
                    int stone = state.optInt("stone", 10) + 5;
                    try {
                        state.put("unlocked_stage", unlocked);
                        state.put("coin", coin);
                        state.put("capsule", cap);
                        state.put("stone", stone);
                    } catch (Throwable ignored) {}
                    writeSave();
                    String starsLua = packStars(unlocked);
                    String sync = "{[2]={{coin=" + coin + ",capsule=" + cap + ",stone=" + stone + ",}},"
                            + "[7]={[2]=-1,[3]=" + unlocked + ",[4]={" + starsLua + "},},"
                            + "[8]={[1]={_={},},[3]=0,[4]=\"\",[5]=0,[6]={_={},},},}";
                    byte[] comp = deflateRaw(sync);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    bos.write(new byte[]{2, 0, 1, 0}, 0, 4);
                    bos.write(sess, 0, 2);
                    bos.write(new byte[]{6, 0, 4, 0, 0, 0, 8, 0, 0, 0, 1, 0, 0, 0}, 0, 14);
                    bos.write(leInt(comp.length), 0, 4);
                    bos.write(comp, 0, comp.length);
                    bos.write(new byte[8], 0, 8);
                    os.write(framePkt(bos.toByteArray()));
                } else {
                    List<byte[]> rlist = protoReplay.get(pt);
                    Integer sc = seen.get(pt);
                    int c = sc == null ? 0 : sc.intValue();
                    if (rlist != null && c < rlist.size()) {
                        seen.put(pt, c + 1);
                        byte[] r = Arrays.copyOf(rlist.get(c), rlist.get(c).length);
                        r[4] = sess[0];
                        r[5] = sess[1];
                        os.write(framePkt(r));
                    } else {
                        os.write(framePkt(new byte[]{2, 0, 1, 0, sess[0], sess[1], 1, 0, 4, 0}));
                    }
                }
                os.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(is);
            closeQuietly(os);
            closeSocket(conn);
        }
    }
}
