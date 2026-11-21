import json, os, re, socket, struct, threading, time, zlib
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

SAVE_FILE = "save_offline.json"

def load_save():
    if os.path.exists(SAVE_FILE):
        return json.load(open(SAVE_FILE, "r"))
    return {"unlocked_stage": 1, "coin": 5000, "capsule": 10, "stone": 10}

def write_save(st):
    json.dump(st, open(SAVE_FILE, "w"), indent=2)


def pack_stars(unlocked):
    arr = [0, 0, 0, 0, 0]
    for st in range(1, unlocked):
        idx = (st - 1) // 15
        shift = ((st - 1) % 15) * 2
        if idx < len(arr):
            arr[idx] |= (3 << shift)
    return arr

STATE = load_save()

def load_stage_rooms():
    rooms = {}
    path = "out_lua/config/stage.txt"
    if not os.path.exists(path): return rooms
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        lines = f.readlines()
    if len(lines) < 5: return rooms
    headers = lines[1].rstrip("\r\n").split("\t")
    rl_idx = headers.index("roomList") if "roomList" in headers else 8
    for line in lines[4:]:
        cols = line.rstrip("\r\n").split("\t")
        if len(cols) > rl_idx and cols[0] and cols[rl_idx]:
            rooms[cols[0]] = [r.strip() for r in cols[rl_idx].split(";") if r.strip()]
    return rooms

STAGE_ROOMS = load_stage_rooms()
print(f"[*] Đã nạp cấu trúc phòng cho {len(STAGE_ROOMS)} màn từ stage.txt")


def sproto_pack(data):
    if len(data) % 8 != 0:
        data += b"\x00" * (8 - len(data) % 8)
    out = bytearray()
    for i in range(0, len(data), 8):
        chunk = data[i:i+8]
        hdr = 0
        nz = bytearray()
        for b_idx, val in enumerate(chunk):
            if val != 0:
                hdr |= (1 << b_idx)
                nz.append(val)
        out.append(hdr)
        if hdr == 0xff:
            out.append(0)
        out.extend(nz)
    return bytes(out)

def sproto_unpack(data):
    out = bytearray()
    i, n = 0, len(data)
    while i < n:
        hdr = data[i]; i += 1
        if hdr == 0xff:
            if i >= n: break
            cnt = (data[i] + 1) * 8; i += 1
            out.extend(data[i:i+cnt]); i += cnt
        else:
            for bit in range(8):
                if (hdr >> bit) & 1:
                    if i < n: out.append(data[i]); i += 1
                else:
                    out.append(0)
    return bytes(out)

def deflate_raw(text_str):
    comp = zlib.compressobj(level=6, wbits=-15)
    return comp.compress(text_str.encode("utf-8")) + comp.flush()

def frame_pkt(unpacked_bytes):
    packed = sproto_pack(unpacked_bytes)
    return struct.pack(">H", len(packed)) + packed

def build_sync_response(sess, lua_sync_str):
    comp = deflate_raw(lua_sync_str)
    body = b"\x02\x00\x01\x00" + sess + b"\x02\x00\x04\x00\x00\x00" + struct.pack("<I", len(comp)) + comp
    return frame_pkt(body)

def build_login_response(session_raw):
    raw = open("login_s2c_unpacked.bin", "rb").read()
    raw = raw[:4] + session_raw + raw[6:]
    if os.path.exists("role_data.txt"):
        role_txt = open("role_data.txt", "r", encoding="utf-8").read()
        # Cập nhật màn đã mở vào role_data (trường [7][3])
        stars_str = ",".join(str(x) for x in pack_stars(STATE["unlocked_stage"]))
        role_txt = re.sub(
            r'(\{\{\{0\}\},1,)\d+,\{[0-9,]+\}',
            rf'\g<1>{STATE["unlocked_stage"]},{{{stars_str}}}',
            role_txt,
            count=1
        )
        skin_idx = STATE.get("skin", 1)
        role_txt = re.sub(r',"",0,\d+\}', f',"",0,{skin_idx}}}', role_txt, count=1)
        new_comp = deflate_raw(role_txt)
        pos = raw.find(b"\x9f\x02\x00\x00")
        if pos != -1:
            raw = raw[:pos] + struct.pack("<I", len(new_comp)) + new_comp + raw[pos + 4 + 671:]
    return frame_pkt(raw)

def parse_frames(buf):
    frames, i = [], 0
    while i + 2 <= len(buf):
        ln = struct.unpack(">H", buf[i:i+2])[0]
        if i + 2 + ln > len(buf): break
        frames.append(buf[i+2:i+2+ln])
        i += 2 + ln
    return frames, buf[i:]

def load_proto_replay(path):
    c2s_buf, s2c_buf = b"", b""
    sess_to_proto, proto_replay = {}, {}
    for line in open(path, "r"):
        line = line.strip()
        if "|" not in line: continue
        tag, hx = line.split("|", 1)
        raw = bytes.fromhex(hx)
        if tag == "C2S":
            c2s_buf += raw
            frames, c2s_buf = parse_frames(c2s_buf)
            for body in frames:
                u = sproto_unpack(body)
                if len(u) >= 6:
                    pt = (struct.unpack("<H", u[2:4])[0] // 2) - 1
                    sess_to_proto[u[4:6]] = pt
        elif tag == "S2C":
            s2c_buf += raw
            frames, s2c_buf = parse_frames(s2c_buf)
            for body in frames:
                u = sproto_unpack(body)
                if len(u) >= 6 and u[4:6] in sess_to_proto:
                    pt = sess_to_proto[u[4:6]]
                    proto_replay.setdefault(pt, []).append(u)
    return proto_replay

PROTO_REPLAY = load_proto_replay("login_capture.log")

class OfflineRouteHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        if length > 0: self.rfile.read(length)
        resp = b'{"url":"127.0.0.1|2345"}' if self.path.startswith("/route") else b'{"code":0,"data":[]}'
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(resp)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(resp)
        except BrokenPipeError:
            pass
    def log_message(self, format, *args):
        pass

def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk: return None
        buf += chunk
    return buf

def handle_client(conn):
    print(f"[+] Game kết nối Offline! (Màn đã mở: {STATE['unlocked_stage']})")
    seen = {}
    idx = 0
    current_stage_num = 1
    current_room = 1
    try:
        while True:
            hdr = recv_exact(conn, 2)
            if not hdr: break
            ln = struct.unpack(">H", hdr)[0]
            body = recv_exact(conn, ln)
            if body is None: break

            idx += 1
            unpacked = sproto_unpack(body)
            pt = (struct.unpack("<H", unpacked[2:4])[0] // 2) - 1 if len(unpacked) >= 6 else -1
            sess = unpacked[4:6] if len(unpacked) >= 6 else b"\x02\x00"

            if pt == 3: # LOGIN
                pkt = build_login_response(sess)
                conn.sendall(pkt)
                print(f"[#{idx} LOGIN] Gửi role_data (unlocked_stage={STATE['unlocked_stage']})")

            elif pt == 6: # HEART
                now = int(time.time())
                heart_body = b"\x02\x00\x01\x00" + sess + b"\x01\x00\x00\x00\x04\x00\x00\x00" + struct.pack("<I", now)
                conn.sendall(frame_pkt(heart_body))

            elif pt == 201: # ENTER STAGE
                m = re.search(rb"sm_(\d+)", unpacked)
                if m:
                    current_stage_num = int(m.group(1))
                now = int(time.time())
                current_room = 1
                stage_str = f"sm_{current_stage_num}"
                room_list = STAGE_ROOMS.get(stage_str, [])
                map_str = room_list[0] if room_list else f"m{current_stage_num}_1"
                sync_lua = f'{{[8]={{{{_={{}},}},"{map_str}",1,"{stage_str}",{now},{{_={{tm=0,dt=0,ce=0,ctl={{}},}},}}}},[24]={{[3]={now},}},}}'
                conn.sendall(build_sync_response(sess, sync_lua))
                print(f"[#{idx} ENTER STAGE] Bắt đầu màn {stage_str} ({map_str})")


            elif pt == 207: # ENTER NEXT ROOM (Tự động theo stage.txt)
                stage_str = f"sm_{current_stage_num}"
                room_list = STAGE_ROOMS.get(stage_str, [])
                max_rooms = len(room_list) if room_list else 10
                if current_room < max_rooms:
                    current_room += 1
                    next_map = room_list[current_room - 1] if room_list else f"m{current_stage_num}_{current_room}"
                    sync_lua = f"{{[8]={{[3]={current_room},[2]=\"{next_map}\",}},[7]={{[2]={current_room-1},}},}}"
                    conn.sendall(build_sync_response(sess, sync_lua))
                    print(f"[#{idx} NEXT ROOM] {stage_str} -> {next_map} ({current_room}/{max_rooms})")
                else:
                    ok_body = b"\x02\x00\x01\x00" + sess + b"\x01\x00\x04\x00"
                    conn.sendall(frame_pkt(ok_body))
                    print(f"[#{idx} MAX ROOM] Đã xong phòng cuối {current_room}/{max_rooms} của {stage_str}")


            elif pt == 128: # WEAR_SKIN
                m = re.search(rb"skin_(\d+)", unpacked)
                if m:
                    skin_idx = int(m.group(1))
                else:
                    # Trường hợp client gửi dạng số nguyên (index)
                    skin_idx = (struct.unpack("<H", unpacked[8:10])[0] // 2) - 1 if len(unpacked) >= 10 else 1
                STATE["skin"] = skin_idx
                write_save(STATE)
                stars_lua = ",".join(str(x) for x in pack_stars(STATE["unlocked_stage"]))
                sync_lua = f"{{[3]={{[18]={skin_idx},}},}}"
                conn.sendall(build_sync_response(sess, sync_lua))
                print(f"[#{idx} WEAR_SKIN] Đã mặc skin #{skin_idx} | raw={unpacked[:30]}")

            elif pt == 202: # FINISH STAGE
                m = re.search(rb"sm_(\d+)", unpacked)
                cleared = int(m.group(1)) if m else current_stage_num
                stars = STATE.setdefault("stars", [0, 0, 0, 0, 0])
                while len(stars) < cleared:
                    stars.append(0)
                stars[cleared - 1] = 3
                if cleared >= STATE["unlocked_stage"]:
                    STATE["unlocked_stage"] = cleared + 1
                STATE["coin"] += 3000
                STATE["capsule"] += 5
                STATE["stone"] += 5
                write_save(STATE)
                stars_lua = ",".join(str(x) for x in pack_stars(STATE["unlocked_stage"]))
                sync_lua = (
                    f'{{[2]={{{{coin={STATE["coin"]},capsule={STATE["capsule"]},stone={STATE["stone"]},}}}},'
                    f'[7]={{[2]=-1,[3]={STATE["unlocked_stage"]},[4]={{{stars_lua}}},}},'
                    f'[8]={{[1]={{_={{}},}},[3]=0,[4]="",[5]=0,[6]={{_={{}},}},}},}}'
                )
                comp = deflate_raw(sync_lua)
                tail_202 = b"\x00\x00\x00\x00\x00\x00\x00\x00"
                body_202 = b"\x02\x00\x01\x00" + sess + b"\x06\x00\x04\x00\x00\x00\x08\x00\x00\x00\x01\x00\x00\x00" + struct.pack("<I", len(comp)) + comp + tail_202
                conn.sendall(frame_pkt(body_202))
                print(f"[#{idx} CLEAR STAGE] Thắng sm_{cleared} -> Đã mở màn {STATE['unlocked_stage']} | Coin={STATE['coin']}")

            elif pt in PROTO_REPLAY and seen.get(pt, 0) < len(PROTO_REPLAY[pt]):
                c = seen.get(pt, 0)
                seen[pt] = c + 1
                resp_u = PROTO_REPLAY[pt][c]
                resp_u = resp_u[:4] + sess + resp_u[6:]
                conn.sendall(frame_pkt(resp_u))
                print(f"[#{idx} REPLAY] tag={pt} (#{c+1})")

            else:
                ok_body = b"\x02\x00\x01\x00" + sess + b"\x01\x00\x04\x00"
                conn.sendall(frame_pkt(ok_body))
                print(f"[#{idx} AUTO-OK] tag={pt} (len={ln}B)")
    except Exception as e:
        print(f"[!] Ngắt kết nối: {e}")
    finally:
        conn.close()

threading.Thread(
    target=lambda: ThreadingHTTPServer(("127.0.0.1", 12034), OfflineRouteHandler).serve_forever(),
    daemon=True
).start()

srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", 2345))
srv.listen(5)
print("[*] OFFLINE SERVER (Auto-Unlock & Save) sẵn sàng tại 127.0.0.1")

while True:
    c, _ = srv.accept()
    threading.Thread(target=handle_client, args=(c,), daemon=True).start()
