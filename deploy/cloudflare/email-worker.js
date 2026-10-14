/**
 * PayLedger auto-capture: a Cloudflare Email Worker (docs/hosting.md, "Auto-capture").
 *
 * Cloudflare Email Routing hands every email sent to <anything>@<capture domain> to this Worker, which posts the raw
 * message to PayLedger with a shared secret. PayLedger finds the person by the address it was sent to, reads the
 * bank alert, and puts it in their "To review" list. Nothing is stored here.
 *
 * Settings (Worker > Settings > Variables):
 *   INBOUND_URL     https://payledger.example.com/inbound/email
 *   INBOUND_SECRET  the same long random value as INBOUND_SECRET in PayLedger's .env (add it as a secret)
 */
export default {
  async email(message, env) {
    // Bank alerts are small; anything huge is not an alert (and Cloudflare caps emails at 25 MB anyway).
    if (message.rawSize > 512 * 1024) {
      message.setReject('Too large')
      return
    }
    const raw = await new Response(message.raw).arrayBuffer()
    const res = await fetch(env.INBOUND_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'message/rfc822',
        'X-Inbound-Secret': env.INBOUND_SECRET,
        // The address it was sent to (a forwarded email's own To: header is the person's Gmail address).
        'X-Envelope-To': message.to,
      },
      body: raw,
    })
    if (res.status >= 500) {
      // PayLedger is down (the laptop is off): a temporary failure, so the sender's server tries again later.
      throw new Error(`PayLedger answered ${res.status}`)
    }
  },
}
