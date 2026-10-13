import { useEffect, useState, type FormEvent } from 'react'
import { Eye, EyeOff, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/controls'
import { Dialog } from '@/components/ui/overlay'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useCategories, useCreateCategory, useDeleteCategory, useUpdateCategory, type Category } from '@/state/money'
import { CATEGORY_COLORS, CATEGORY_ICONS, Field, Select } from './parts'

/** Add a category, or rename it, change its icon or colour, move it under another, hide or delete it. */
export function CategoryDialog({ open, onOpenChange, category, kind }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  category?: Category
  /** For a new category. */
  kind: Category['kind']
}) {
  const { data: categories = [] } = useCategories()
  const create = useCreateCategory()
  const update = useUpdateCategory()
  const remove = useDeleteCategory()
  const [name, setName] = useState('')
  const [icon, setIcon] = useState('tag')
  const [color, setColor] = useState<string>('series-1')
  const [parentId, setParentId] = useState('')
  const [error, setError] = useState<string>()
  const [confirmDelete, setConfirmDelete] = useState(false)

  useEffect(() => {
    if (!open) return
    setName(category?.name ?? '')
    setIcon(category?.icon ?? 'tag')
    setColor(category?.color ?? 'series-1')
    setParentId(category?.parentId ?? '')
    setError(undefined)
    setConfirmDelete(false)
  }, [open, category])

  const theKind = category?.kind ?? kind
  const hasChildren = !!category && categories.some((c) => c.parentId === category.id)
  // Subcategories go one level deep: a parent is a visible top-level category of the same kind.
  const parents = categories.filter((c) => c.kind === theKind && !c.parentId && c.id !== category?.id
    && (!c.hidden || c.id === category?.parentId))
  const busy = create.isPending || update.isPending || remove.isPending

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    if (!name.trim()) return setError('Give the category a name, like "Pets" or "Gym".')
    const fields = { name: name.trim(), icon, color, parentId: parentId || null }
    try {
      if (category) {
        await update.mutateAsync({ id: category.id, ...fields, hidden: category.hidden })
        toast.success('Saved')
      } else {
        await create.mutateAsync({ kind: theKind, ...fields })
        toast.success(`${fields.name} added`)
      }
      onOpenChange(false)
    } catch (err) {
      setError(message(err))
    }
  }

  async function toggleHidden() {
    if (!category) return
    try {
      await update.mutateAsync({
        id: category.id, name: category.name, icon: category.icon, color: category.color,
        parentId: category.parentId, hidden: !category.hidden,
      })
      toast(category.hidden ? `${category.name} is back` : `${category.name} hidden`, {
        description: category.hidden ? undefined : 'Its history stays in your reports; it is no longer offered for new transactions.',
      })
      onOpenChange(false)
    } catch (err) {
      setError(message(err))
    }
  }

  async function deleteIt() {
    if (!category) return
    if (!confirmDelete) return setConfirmDelete(true)
    try {
      await remove.mutateAsync(category.id)
      toast(`${category.name} deleted`)
      onOpenChange(false)
    } catch (err) {
      setConfirmDelete(false)
      setError(message(err))
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}
      title={category ? `Edit ${category.name}` : theKind === 'INCOME' ? 'Add an income category' : 'Add a spending category'}>
      <form onSubmit={submit} className="flex flex-col gap-4 p-pad" noValidate>
        <Field label="Name" htmlFor="category-name">
          <Input id="category-name" maxLength={40} autoFocus placeholder={theKind === 'INCOME' ? 'Freelance' : 'Pets'} value={name}
            onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Inside" htmlFor="category-parent"
          hint={hasChildren ? 'It has subcategories, so it stays at the top.' : 'Optional: make it a subcategory, like Groceries › Snacks.'}>
          <Select id="category-parent" value={parentId} disabled={hasChildren} onChange={(e) => setParentId(e.target.value)}>
            <option value="">Nothing (top level)</option>
            {parents.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </Select>
        </Field>
        <fieldset>
          <legend className="mb-1.5 text-sm font-medium">Icon</legend>
          <div className="grid grid-cols-8 gap-1 sm:grid-cols-10">
            {Object.entries(CATEGORY_ICONS).map(([key, Icon]) => (
              <label key={key} title={key} className={cn(
                'flex aspect-square cursor-pointer items-center justify-center rounded-control text-muted hover:bg-surface-2',
                'has-[:checked]:bg-accent-soft has-[:checked]:text-fg has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring',
              )}>
                <input type="radio" name="category-icon" value={key} checked={icon === key} onChange={() => setIcon(key)}
                  className="sr-only" aria-label={key.replace(/-/g, ' ')} />
                <Icon className="size-4" />
              </label>
            ))}
          </div>
        </fieldset>
        <fieldset>
          <legend className="mb-1.5 text-sm font-medium">Colour</legend>
          <p className="mb-2 text-xs text-subtle">It is also the category's colour in charts.</p>
          <div className="flex flex-wrap gap-2">
            {CATEGORY_COLORS.map((c, i) => (
              <label key={c} title={`Colour ${i + 1}`} className="cursor-pointer rounded-full p-0.5 ring-offset-2 ring-offset-surface has-[:checked]:ring-2 has-[:checked]:ring-fg has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring">
                <input type="radio" name="category-color" value={c} checked={color === c} onChange={() => setColor(c)}
                  className="sr-only" aria-label={`Colour ${i + 1}`} />
                <span className="block size-7 rounded-full" style={{ background: `var(--${c})` }} />
              </label>
            ))}
          </div>
        </fieldset>
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
        <div className="flex flex-wrap items-center gap-2">
          {category && (
            <>
              <Button variant="ghost" onClick={toggleHidden} disabled={busy}>
                {category.hidden ? <><Eye />Show</> : <><EyeOff />Hide</>}
              </Button>
              <Button variant={confirmDelete ? 'danger' : 'ghost'} onClick={deleteIt} disabled={busy}>
                <Trash2 />{confirmDelete ? 'Delete for good' : 'Delete'}
              </Button>
            </>
          )}
          <span className="flex-1" />
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={busy}>{category ? 'Save' : 'Add category'}</Button>
        </div>
      </form>
    </Dialog>
  )
}

function message(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 'category-name-in-use') return 'You already have a category with that name.'
    if (err.code === 'category-has-subcategories') return 'It has subcategories. Move or delete them first.'
    if (err.code === 'category-in-use') return 'It has transactions, so it can’t be deleted. Hide it instead: its history stays in your reports.'
    if (err.code === 'category-parent-invalid') return `Can’t put it there: ${err.message}.`
    return err.message
  }
  return 'Could not save. Try again.'
}
