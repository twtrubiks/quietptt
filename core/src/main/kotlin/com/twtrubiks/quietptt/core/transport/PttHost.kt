package com.twtrubiks.quietptt.core.transport

/**
 * ptt.cc 的連線位址與 SSH host key。
 *
 * Host key 直接寫死（pin）：對不上就拒絕連線，不會問使用者「要不要信任」。
 * 站方換 key 時這裡要跟著改、發一版；在自己電腦上用 `ssh-keyscan -t rsa,ecdsa,ed25519 ptt.cc` 重抓。
 * 指紋（SHA-256）：
 *   RSA     9/gkjxWjuteMj//K0sRo1gj7h5qTp0HPIH3n7+e3Jqc
 *   ECDSA   3rvcAywUJszX7jPzC3JkbtxqnHiApB4tuwQ3swSYmSo
 *   ED25519 oTf7wY7klc9gi0FHhYQNHQu7aJfYGz/bFLSpdOxhbxg
 */
object PttHost {
    const val NAME = "ptt.cc"
    const val SSH_PORT = 22

    /** SSH 的使用者名稱：`bbs` 吐 Big5-UAO，跟 WebSocket 一樣；`bbsu` 會吐 UTF-8，先不用（見 docs/ideas.md）。 */
    const val SSH_USER = "bbs"

    const val WS_URL = "wss://ws.ptt.cc/bbs"

    /** ws.ptt.cc 只接受這個 Origin。 */
    const val WS_ORIGIN = "https://term.ptt.cc"

    /** known_hosts 格式，給 JSch 的 `setKnownHosts`。 */
    val KNOWN_HOSTS: String = listOf(
        "$NAME ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDo0nuxItD608H2uKP65MiYmbGMVCC29j4GKLNhoebzPcHQiTCOi25ksD6KsoVPkaI+YTl8ms42PqLVxlaeJFUaekg9FP4BCZA2C7MO76d5wkMvZ8qfOwu9qjMy15WRO+yhGGM9O2TQ3th/dZKFbJnAAGwIAq4vpfXB6GLTE+BdzuMvNp3rzxh9DK2CcOfD6ICSTzbQOMxPA5m/PBEl2UpTzl830bZhcRGEFfWPgqSrcSqZznTYJH85epGqBrLrY4hmM0pbBDRaJRM8bsz99vDSGBat/TbWm4Ye0wMSrlG4rDm59hoBpl7+U3cmLGwX2YlcLXp3yWMpCo57g5Xnz3FAinEwVo+iDY52AzmOZV9f+tWZZlzaiHyI3EPAS0LTuVU1ElXjEWOj10rylsIwPeYPsNKbDm12ZKjHg2Bwm3scfwe5Fy5OhbcuAqm8xKo1wpM/9EfmNZJLzHhQDnBIAAwUR7yARjIhMJzQQJI6YuMTMrvGVNB8aj8oDYEl3o9If5E=",
        "$NAME ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBF2BVrQ8abQ5CEeUEfUybHXFlaFkLwWBfiLN53KnTGyTpJbUCrpTTPHIr325IaKhed+Lx2POwrDwpga8USPBoqc=",
        "$NAME ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDqjN1kJZrgrY6skGqVGT/JHeoZRuTlnRO38IUKEzaW0",
    ).joinToString("\n", postfix = "\n")
}
