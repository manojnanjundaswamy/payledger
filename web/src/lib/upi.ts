/** A UPI id (VPA): name@bank. The same rule as the server's. */
export const UPI_ID = /^[a-zA-Z0-9._-]{2,64}@[a-zA-Z][a-zA-Z0-9]{1,63}$/

/**
 * A UPI payment link (NPCI's upi://pay deep link). On a phone it opens any UPI app (GPay, PhonePe, Paytm, BHIM) with the
 * payee, amount and note filled in; the payer only confirms with their PIN. Nothing goes through PayLedger.
 *
 * @param amount paise
 */
export function upiPayLink(upiId: string, payeeName: string, amount: number, note: string): string {
  const q = new URLSearchParams({ pa: upiId, pn: payeeName, am: (amount / 100).toFixed(2), cu: 'INR', tn: note.slice(0, 50) })
  return `upi://pay?${q.toString().replace(/\+/g, '%20')}`
}
