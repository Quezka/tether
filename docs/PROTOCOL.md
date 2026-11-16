# Tether protocol (version 1)

Both sides: `suite-rs/crates/tether/src/domain` (Rust) and `app/src/main/java/io/github/quezka/tether/Protocol.kt`.

## Finding each other
The computer broadcasts `TETHER1|<name>|<port>|<computer id>` on UDP 47820 every 2 s. The link port is 47821 (TCP).

## Frames
Every frame: 4-byte big-endian length (1 … 4 MiB), then the payload.

## Handshake
1. Phone → `{"t":"hello","id":<phone id>,"name":..,"nonce":b64(16 bytes),"pairing":bool}` (plain JSON).
2. Computer → `{"t":"hello","name":..,"nonce":b64(16 bytes)}` (plain JSON). The computer answers only if
   pairing and a code is showing, or if the phone id is paired.
3. Key = SHA-256("tether1 session" ‖ u32be(len(secret)) ‖ secret ‖ phone nonce ‖ computer nonce). The secret is
   the 8-character code (ASCII, lower case) when pairing, the 32-byte long-term key otherwise.
4. From here every payload is AES-256-GCM: 12-byte random nonce ‖ ciphertext ‖ 16-byte tag.
5. Phone → `{"t":"auth"}`. If it doesn't decrypt, the computer closes.
6. Pairing only: computer → `{"t":"paired","key":b64(32 random bytes),"id":<computer id>}`; both keep the key.

## Messages (JSON, field `t`)
Phone → computer: `notif {key, app, title, text, reply}`, `notif_removed {key}`, `sms_list {threads:[{addr,name,
messages:[{in,body,at}]}]}`, `sms_in {addr,name,body,at}`, `call {state: ringing|offhook|idle, number, name}`,
`battery {level, charging}`, `clip {text}`, `ping`.
Computer → phone: `reply {key,text}`, `dismiss {key}`, `sms_send {addr,body}`, `sms_get`, `call_answer`,
`call_reject`, `clip {text}`.
Both ways: `file_start {id,name,size}`, `file_piece {id,data:b64}`, `file_end {id}` (pieces ≤ 1 MiB).

## Relay (away from Wi-Fi, 0.3)
Both sides dial `relay:47822` (TCP) and send one line `TETHERRELAY1 <pc|phone> <room>\n`, room = first 16 bytes of
SHA-256("tether relay" ‖ long-term key) as hex. When a `pc` and a `phone` with the same room are both there, the relay
writes `ok\n` to each and pipes bytes both ways; from then on it's the normal handshake above (phone speaks first).
The computer keeps one waiting relay connection per paired phone; the phone uses the relay only when it can't reach
the computer on the Wi-Fi. Pairing needs the Wi-Fi (the code never goes through the relay).
