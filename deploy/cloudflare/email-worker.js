/**
 * PayLedger auto-capture: a Cloudflare Email Worker (docs/hosting.md, "Auto-capture").
 *
 * Cloudflare Email Routing hands every email sent to <anything>@<capture domain> to this Worker, which posts the raw
 * message to PayLedger with a shared secret. PayLedger finds the person by the address it was sent to, reads the
 * bank alert, and puts it in their "To review" list. Nothing is stored here.
 *
 * Settings (Worker > Settings > Variables):
 *   INBOUND_URL     https://payledger.example.com/inbound/email
 *   INBOUND_SECRET  the same long random value as INBOUND_SECRET in PayLedger's .env (a secret)
 *   CAPTURE_DOMAIN  in.example.com: only mail for this domain goes to PayLedger. Email Routing's catch-all covers the
 *                   whole zone, so mail for the main domain may arrive here too; it is bounced, not silently lost.
 */
export default {
  async email(message, env) {
    const to = String(message.to || '').toLowerCase()
    if (env.CAPTURE_DOMAIN && !to.endsWith('@' + env.CAPTURE_DOMAIN.toLowerCase())) {
      message.setReject('No such address')
      return
    }
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
    if (res.ok) {
      // No PayLedger address like that (a typo, or one that was replaced): tell the sender.
      const answer = await res.json().catch(() => ({}))
      if (answer.result === 'unknown-address' || answer.result === 'no-recipient') {
        message.setReject('No such address')
      }
      return
    }
    if (res.status === 404) {
      // The shared secret doesn't match PayLedger's: a setup mistake. Fail for now, so the mail is retried once fixed.
      throw new Error('PayLedger refused the secret (INBOUND_SECRET differs)')
    }
    if (res.status >= 500) {
      // PayLedger is down (the laptop is off): a temporary failure, so the sender's server tries again later.
      throw new Error(`PayLedger answered ${res.status}`)
    }
  },
}
