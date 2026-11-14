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
