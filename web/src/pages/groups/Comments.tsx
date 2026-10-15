import { useState, type FormEvent } from 'react'
import { MessageCircle, Send, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/controls'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useAddComment, useComments, useDeleteComment } from '@/state/groups'

/** The talk about one expense ("was this Friday's dinner?"), newest at the bottom, with a box to add to it. */
export function ExpenseComments({ groupId, expenseId, base }: { groupId: string; expenseId: string; base?: string }) {
  const { data } = useComments(groupId, base)
  const add = useAddComment(groupId, base)
  const remove = useDeleteComment(groupId)
  const [body, setBody] = useState('')
  const [error, setError] = useState<string>()
  const thread = (data ?? []).filter((c) => c.expenseId === expenseId)

  async function send(e: FormEvent) {
    e.preventDefault()
    if (!body.trim()) return
    setError(undefined)
    try {
      await add.mutateAsync({ expenseId, body: body.trim() })
      setBody('')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not send it.')
    }
  }
  return (
    <section className="flex flex-col gap-2 border-t border-border pt-3" aria-label="Comments" data-testid="expense-comments">
      <h3 className="flex items-center gap-1.5 text-sm font-medium"><MessageCircle className="size-4" />Comments{thread.length > 0 && ` (${thread.length})`}</h3>
      {thread.length > 0 && (
        <ul className="flex max-h-56 flex-col gap-2 overflow-y-auto">
          {thread.map((c) => (
            <li key={c.id} className={cn('group flex max-w-[85%] flex-col rounded-card px-3 py-2 text-sm', c.you ? 'self-end bg-accent-soft' : 'self-start bg-surface-2')}>
              <span className="text-[0.6875rem] text-muted">{c.you ? 'You' : c.name} · {new Date(c.at).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })}</span>
              <span className="break-words whitespace-pre-wrap">{c.body}</span>
              {c.you && !base?.includes('guest') && (
                <button type="button" className="self-end text-[0.6875rem] text-subtle opacity-0 group-hover:opacity-100 focus:opacity-100 hover:text-critical"
                  onClick={() => remove.mutate(c.id)} aria-label="Delete your comment"><Trash2 className="inline size-3" /> Delete</button>
              )}
            </li>
          ))}
        </ul>
      )}
      <form onSubmit={send} className="flex gap-2">
        <Input value={body} onChange={(e) => setBody(e.target.value)} maxLength={500} placeholder="Ask or add a note…" aria-label="Write a comment" />
        <Button type="submit" variant="outline" size="icon" aria-label="Send comment" disabled={add.isPending || !body.trim()}><Send /></Button>
      </form>
      {error && <p className="text-xs text-critical" role="alert">{error}</p>}
    </section>
  )
}
