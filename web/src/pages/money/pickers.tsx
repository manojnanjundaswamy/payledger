import { useState } from 'react'
import { SearchSelect, type SearchOption } from '@/components/ui/search-select'
import type { Category } from '@/state/money'
import { AccountDialog } from './AccountDialog'
import { CategoryDialog } from './CategoryDialog'

type Common = {
  id?: string
  value: string
  onChange: (value: string) => void
  options: SearchOption[]
  placeholder?: string
  className?: string
  'aria-label'?: string
  /** Where a new one goes, if not the space on screen. */
  space?: string
}

/** An account dropdown whose last row is "Add a new account…": the new account is chosen when its form closes. */
export function AccountPicker({ space, ...props }: Common) {
  const [adding, setAdding] = useState<string | null>(null)
  return (
    <>
      <SearchSelect {...props} onAddNew={(typed) => setAdding(typed)} addNewLabel="Add a new account…" />
      <AccountDialog open={adding !== null} onOpenChange={(o) => !o && setAdding(null)} initialName={adding ?? ''} space={space}
        onCreated={(a) => props.onChange(a.id)} />
    </>
  )
}

/** A category dropdown whose last row is "Add a new category…" (of the kind needed). */
export function CategoryPicker({ space, kind, ...props }: Common & { kind: Category['kind'] }) {
  const [adding, setAdding] = useState<string | null>(null)
  return (
    <>
      <SearchSelect {...props} onAddNew={(typed) => setAdding(typed)} addNewLabel="Add a new category…" />
      <CategoryDialog open={adding !== null} onOpenChange={(o) => !o && setAdding(null)} kind={kind} initialName={adding ?? ''} space={space}
        onCreated={(c) => props.onChange(c.id)} />
    </>
  )
}
