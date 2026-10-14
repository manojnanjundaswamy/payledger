import { useState, type FormEvent } from 'react'
import { History, Sparkles, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/controls'
import { SearchSelect } from '@/components/ui/search-select'
import { ApiError } from '@/lib/api'
import { useApplyCategoryRule, useCategoryRules, useDeleteCategoryRule, useSaveCategoryRule, type Category } from '@/state/money'
import { CategoryIcon, categoryOptions } from './parts'

/**
 * Rules: "when a description contains these words, use this category". They fill in the category as you type a new
 * transaction and when you import a file, and can move past transactions too.
 */
export function CategoryRules({ categories }: { categories: Category[] }) {
  const { data: rules = [] } = useCategoryRules()
  const save = useSaveCategoryRule()
  const remove = useDeleteCategoryRule()
  const apply = useApplyCategoryRule()
  const [match, setMatch] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [error, setError] = useState<string>()
  const choices = categories.filter((c) => !c.hidden)

  async function add(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    if (match.trim().length < 2) return setError('Type a word or two from the description, like "swiggy".')
    if (!categoryId) return setError('Choose the category.')
    try {
      await save.mutateAsync({ match: match.trim(), categoryId })
      setMatch('')
      setCategoryId('')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save it. Try again.')
    }
  }

  async function applyRule(id: string, words: string) {
    try {
      const { applied } = await apply.mutateAsync(id)
      toast.success(applied === 0 ? `Nothing else to move for “${words}”` : `${applied} past transaction${applied === 1 ? '' : 's'} moved`)
    } catch {
      toast.error('Could not apply it. Try again.')
    }
  }

  return (
    <section aria-label="Rules" className="flex flex-col gap-2">
      <h2 className="flex items-center gap-1.5 text-sm font-medium text-muted"><Sparkles className="size-4" />Rules</h2>
      <p className="text-xs text-subtle">When a description contains these words, the category is filled in for you: as you add a transaction and when you import a file.</p>
      <Card className="divide-y divide-border p-0">
        <form onSubmit={add} className="grid gap-2 p-pad sm:grid-cols-[1fr_1fr_auto] sm:items-end" noValidate>
          <label className="flex flex-col gap-1.5 text-sm font-medium">When it contains
            <Input value={match} maxLength={60} placeholder="swiggy" onChange={(e) => setMatch(e.target.value)} />
          </label>
          <div className="flex flex-col gap-1.5 text-sm font-medium">
            <span>Use the category</span>
            <SearchSelect aria-label="Use the category" value={categoryId} onChange={setCategoryId} options={categoryOptions(choices, categories, { group: true })} />
          </div>
          <Button type="submit" variant="outline" disabled={save.isPending}>Add rule</Button>
          {error && <p className="text-sm text-critical sm:col-span-3" role="alert">{error}</p>}
        </form>
        {rules.length === 0 ? (
          <p className="p-pad text-sm text-muted">No rules yet. Tick “Always use this” when you add a transaction, or add one here.</p>
        ) : rules.map((r) => {
          const c = categories.find((x) => x.id === r.categoryId)
          return (
            <div key={r.id} className="flex flex-wrap items-center gap-3 px-pad py-2.5" data-testid={`rule-${r.match}`}>
              <span className="font-mono text-sm">“{r.match}”</span>
              <span className="text-muted">→</span>
              <span className="flex min-w-0 flex-1 items-center gap-2 text-sm"><CategoryIcon category={c} className="size-7" /><span className="truncate">{c?.name ?? 'Category'}</span></span>
              <Button size="sm" variant="ghost" onClick={() => void applyRule(r.id, r.match)} disabled={apply.isPending}
                title="Move past transactions with these words into this category"><History />Apply to past</Button>
              <Button size="sm" variant="ghost" aria-label={`Delete rule ${r.match}`} onClick={() => remove.mutate(r.id)}><Trash2 /></Button>
            </div>
          )
        })}
      </Card>
    </section>
  )
}
