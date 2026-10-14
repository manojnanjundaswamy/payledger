import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import { Plus, Search } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, Skeleton } from '@/components/ui/card'
import { Input, Segmented } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { categoryTree, useCategories, type Category } from '@/state/money'
import { CategoryDialog } from './CategoryDialog'
import { CategoryIcon, EditButton } from './parts'

type Kind = Category['kind']

/** Spending and income categories, with subcategories under their parent. Hidden ones are listed last. */
export function CategoriesPage() {
  const { data: categories = [], isPending, isError } = useCategories()
  const [kind, setKind] = useState<Kind>('EXPENSE')
  const [editing, setEditing] = useState<Category | 'new' | null>(null)

  const [q, setQ] = useState('')
  const ofKind = categories.filter((c) => c.kind === kind)
  // A search keeps a parent when it or any of its subcategories match, so the tree still reads.
  const needle = q.trim().toLowerCase()
  const hits = (c: Category) => c.name.toLowerCase().includes(needle)
  const matches = (list: ReturnType<typeof categoryTree>) => !needle ? list
    : list.filter((n) => hits(n.category) || n.children.some(hits)).map((n) => ({ ...n, children: hits(n.category) ? n.children : n.children.filter(hits) }))
  const shown = matches(categoryTree(ofKind.filter((c) => !c.hidden)))
  // A hidden parent carries its (also hidden) subcategories; a hidden subcategory of a shown parent stands alone.
  const hidden = matches(categoryTree(ofKind.filter((c) => c.hidden)))

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <Segmented label="Kind" value={kind} onChange={setKind}
          options={[{ value: 'EXPENSE', label: 'Spending' }, { value: 'INCOME', label: 'Income' }]} />
        <label className="relative min-w-0 flex-1 basis-40">
          <span className="sr-only">Search categories</span>
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-subtle" />
          <Input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search categories" className="pl-9" />
        </label>
        <Button variant="primary" onClick={() => setEditing('new')}><Plus />Add category</Button>
      </div>
      {needle && shown.length === 0 && hidden.length === 0 && <Card className="p-pad text-sm text-muted">No {kind === 'EXPENSE' ? 'spending' : 'income'} category matches "{q.trim()}".</Card>}

      {isError && <Card className="p-pad text-sm text-critical">Couldn't load your categories. Refresh to try again.</Card>}
      {isPending && <Skeleton className="h-64" />}

      {!isPending && (
        <section aria-label={kind === 'EXPENSE' ? 'Spending categories' : 'Income categories'}>
          <Card className="divide-y divide-border p-0">
            {shown.map(({ category, children }) => (
              <div key={category.id}>
                <Row category={category} onEdit={setEditing} />
                {children.map((child) => <Row key={child.id} category={child} onEdit={setEditing} sub />)}
              </div>
            ))}
          </Card>
        </section>
      )}

      {hidden.length > 0 && (
        <section aria-label="Hidden" className="flex flex-col gap-2">
          <h2 className="text-sm font-medium text-muted">Hidden</h2>
          <p className="text-xs text-subtle">Not offered for new transactions. Their history stays in your reports.</p>
          <Card className="divide-y divide-border p-0">
            {hidden.map(({ category, children }) => (
              <div key={category.id}>
                <Row category={category} onEdit={setEditing} parent={categories.find((p) => p.id === category.parentId)} />
                {children.map((child) => <Row key={child.id} category={child} onEdit={setEditing} sub />)}
              </div>
            ))}
          </Card>
        </section>
      )}

      <CategoryDialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)} kind={kind}
        category={editing === 'new' || editing === null ? undefined : editing} />
    </div>
  )
}

function Row({ category, onEdit, sub, parent }: { category: Category; onEdit: (c: Category) => void; sub?: boolean; parent?: Category }) {
  return (
    <div className="flex items-center gap-1 pr-2 hover:bg-surface-2">
    <Link to="/transactions" search={{ category: category.id }} aria-label={`${category.name} transactions`}
      className={cn('flex min-w-0 flex-1 items-center gap-3 py-2.5 pl-pad text-left', sub && 'pl-[calc(var(--pad)+2.75rem)]')}>
      <CategoryIcon category={category} className={cn(sub && 'size-7')} />
      <span className="min-w-0 flex-1">
        <span className={cn('block truncate', !sub && 'font-medium')}>{category.name}</span>
        {parent && <span className="block text-xs text-subtle">in {parent.name}</span>}
      </span>
    </Link>
    <EditButton label={`Edit ${category.name}`} onClick={() => onEdit(category)} />
    </div>
  )
}
