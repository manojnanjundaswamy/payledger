/**
 * WhatsApp's click-to-chat link: opens WhatsApp (app or web) with the message typed, and the person picks who to send
 * it to. Free, and needs no WhatsApp Business account (docs/notifications.md has the plan for automatic messages).
 */
export function whatsAppShare(text: string): string {
  return `https://wa.me/?text=${encodeURIComponent(text)}`
}
