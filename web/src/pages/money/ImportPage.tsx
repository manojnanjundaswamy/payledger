import { useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { AlertTriangle, Check, Download, FileUp, Undo2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge, Card } from '@/components/ui/card'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import {
  categoryLabel, problemsCsv, templateUrl, useAccounts, useCategories, useCommitImport, useImports, usePreviewImport,
  useSpaceId, useUndoImport, type ImportPreview, type ImportRequest, type ImportUnknown,
} from '@/state/money'
import { ACCOUNT_KINDS, Select, useMoneyFormat } from './parts'

/** A CSV in the template's shape: checked row by row, unknown names mapped, then imported (and undoable). */
export function ImportPage() {
  const spaceId = useSpaceId()
  const navigate = useNavigate()
  const previewing = usePreviewImport()
  const committing = useCommitImport()
  const [request, setRequest] = useState<ImportRequest | null>(null)
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [error, setError] = useState<string>()

  async function check(next: ImportRequest) {
    setRequest(next)
    setError(undefined)
    try {
      setPreview(await previewing.mutateAsync(next))
    } catch (err) {
      setPreview(null)
      setError(err instanceof ApiError ? err.message.charAt(0).toUpperCase() + err.message.slice(1) + '.' : 'Could not read the file. Try again.')
    }
  }

  async function choose(file: File | undefined) {
    if (!file) return
    if (file.size > 2_000_000) return setError('The file is bigger than 2 MB. Split it into smaller files.')
    void check({ fileName: file.name, csv: await file.text(), accounts: {}, categories: {} })
  }

  async function importIt() {
    if (!request) return
    try {
      const result = await committing.mutateAsync(request)
      toast.success(`${result.imported} transaction${result.imported === 1 ? '' : 's'} imported`, {
        description: [result.duplicates && `${result.duplicates} already there`, result.skipped && `${result.skipped} skipped`]
          .filter(Boolean).join(', ') || undefined,
      })
      void navigate({ to: '/transactions' })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not import. Try again.')
    }
  }

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <Card className="flex flex-col gap-4 p-pad">
        <div>
          <h2 className="font-semibold">Import from a spreadsheet</h2>
          <p className="mt-1 text-sm text-muted">
            Save your sheet or bank statement as CSV in the template's columns: date, amount, type and account are needed;
            category, description, notes and more are optional. Dates like 05-10-2026 and amounts like 1,00,000 are fine.
            Nothing is saved until you press Import.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <label className="inline-flex h-10 cursor-pointer items-center gap-2 rounded-control bg-accent px-4 text-sm font-medium text-accent-fg shadow-sm hover:opacity-90 has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring">
            <FileUp className="size-4" />Choose a CSV file
            <input type="file" accept=".csv,text/csv" className="sr-only" aria-label="CSV file"
              onChange={(e) => { void choose(e.target.files?.[0]); e.target.value = '' }} />
          </label>
          {spaceId && (
            <Button asChild variant="outline"><a href={templateUrl(spaceId)} download><Download />Download the template</a></Button>
          )}
        </div>
        {request && <p className="text-sm text-muted">File: <span className="font-medium text-fg">{request.fileName}</span></p>}
        {previewing.isPending && <p className="text-sm text-muted">Checking every row…</p>}
        {error && <p className="rounded-control bg-critical/10 px-3 py-2 text-sm text-critical" role="alert">{error}</p>}
      </Card>

      {preview && request && (
        <>
          <Summary preview={preview} />
          {(preview.unknownAccounts.length > 0 || preview.unknownCategories.length > 0) && (
            <Mappings preview={preview} request={request} onChange={check} />
          )}
          <Problems preview={preview} fileName={request.fileName} />
          <ReadyRows preview={preview} />
          <div className="flex items-center justify-end gap-2">
            <Button variant="ghost" onClick={() => { setPreview(null); setRequest(null) }}>Cancel</Button>
            <Button variant="primary" disabled={preview.ready === 0 || committing.isPending || previewing.isPending} onClick={importIt}>
              {committing.isPending ? 'Importing…' : `Import ${preview.ready} row${preview.ready === 1 ? '' : 's'}`}
            </Button>
          </div>
        </>
      )}

      <PastImports />
    </div>
  )
}

function Summary({ preview }: { preview: ImportPreview }) {
  const items = [
    { label: 'ready to import', value: preview.ready, tone: 'good' as const },
    { label: 'already there', value: preview.duplicates, tone: 'neutral' as const },
    { label: 'with problems', value: preview.errors, tone: 'critical' as const },
  ]
  return (
    <section aria-label="Summary" className="grid grid-cols-3 gap-3">
      {items.map((i) => (
        <Card key={i.label} className="p-pad">
          <div className={cn('text-2xl font-semibold tabular', i.tone === 'critical' && i.value > 0 && 'text-critical')}
            data-testid={`import-${i.label.replace(/ /g, '-')}`}>{i.value}</div>
          <div className="text-xs text-muted">{i.label}</div>
        </Card>
      ))}
    </section>
  )
}

/** Names the space doesn't have: point each at an existing account or category, or create it. */
function Mappings({ preview, request, onChange }: { preview: ImportPreview; request: ImportRequest; onChange: (r: ImportRequest) => void }) {
  const { data: accounts = [] } = useAccounts()
  const { data: categories = [] } = useCategories()
  const setAccount = (key: string, value: string) => {
    const next = { ...request.accounts }
    if (value) next[key] = value
    else delete next[key]
    onChange({ ...request, accounts: next })
  }
  const setCategory = (key: string, value: string) => {
    const next = { ...request.categories }
    if (value) next[key] = value
    else delete next[key]
    onChange({ ...request, categories: next })
  }
  const rows = (u: ImportUnknown) => `${u.rows} row${u.rows === 1 ? '' : 's'}`
  return (
    <section aria-label="New names" className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted">New names in your file</h2>
      <Card className="divide-y divide-border p-0">
        {preview.unknownAccounts.map((u) => (
          <div key={u.key} className="flex flex-wrap items-center gap-3 px-pad py-3">
            <span className="min-w-0 flex-1">
              <span className="block font-medium">{u.name}</span>
              <span className="block text-xs text-subtle">Account · {rows(u)}</span>
            </span>
            <Select aria-label={`Account for ${u.name}`} className="w-auto min-w-56" value={request.accounts[u.key] ?? ''}
              onChange={(e) => setAccount(u.key, e.target.value)}>
              <option value="">Choose…</option>
              <optgroup label="Create it as">
                {(Object.keys(ACCOUNT_KINDS) as (keyof typeof ACCOUNT_KINDS)[]).map((k) =>
                  <option key={k} value={`new:${k}`}>New {ACCOUNT_KINDS[k].label.toLowerCase()}</option>)}
              </optgroup>
              {accounts.length > 0 && (
                <optgroup label="Same as">
                  {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
                </optgroup>
              )}
            </Select>
          </div>
        ))}
        {preview.unknownCategories.map((u) => (
          <div key={u.key} className="flex flex-wrap items-center gap-3 px-pad py-3">
            <span className="min-w-0 flex-1">
              <span className="block font-medium">{u.parent ? `${u.parent} › ${u.name}` : u.name}</span>
              <span className="block text-xs text-subtle">{u.kind === 'INCOME' ? 'Income' : 'Spending'} category · {rows(u)}</span>
            </span>
            <Select aria-label={`Category for ${u.name}`} className="w-auto min-w-56" value={request.categories[u.key] ?? ''}
              onChange={(e) => setCategory(u.key, e.target.value)}>
              <option value="">Choose…</option>
              <option value="new">Create {u.parent ? `it under ${u.parent}` : 'it'}</option>
              <optgroup label="Same as">
                {categories.filter((c) => c.kind === u.kind && !c.hidden).map((c) =>
                  <option key={c.id} value={c.id}>{categoryLabel(c, categories)}</option>)}
              </optgroup>
            </Select>
          </div>
        ))}
      </Card>
    </section>
  )
}

function Problems({ preview, fileName }: { preview: ImportPreview; fileName: string }) {
  const bad = preview.rows.filter((r) => r.status === 'ERROR')
  if (bad.length === 0) return null
  const download = () => {
    const url = URL.createObjectURL(new Blob([problemsCsv(bad)], { type: 'text/csv' }))
    const a = document.createElement('a')
    a.href = url
    a.download = fileName.replace(/\.csv$/i, '') + '-problems.csv'
    a.click()
    URL.revokeObjectURL(url)
  }
  return (
    <section aria-label="Problems" className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-sm font-medium text-muted">Rows with problems (skipped unless fixed)</h2>
        <Button variant="ghost" size="sm" onClick={download}><Download />Error report</Button>
      </div>
      <Card className="divide-y divide-border p-0">
        {bad.slice(0, 20).map((r) => (
          <div key={r.line} className="flex items-start gap-3 px-pad py-2.5 text-sm">
            <AlertTriangle className="mt-0.5 size-4 shrink-0 text-warning" />
            <span className="w-14 shrink-0 text-muted tabular">Row {r.line}</span>
            <span className="min-w-0 flex-1">{r.problems.map((p) => `${p.column}: ${p.reason}`).join('; ')}</span>
          </div>
        ))}
        {bad.length > 20 && <p className="px-pad py-2.5 text-xs text-subtle">and {bad.length - 20} more in the error report</p>}
      </Card>
    </section>
  )
}

function ReadyRows({ preview }: { preview: ImportPreview }) {
  const money = useMoneyFormat()
  const ready = preview.rows.filter((r) => r.status === 'READY')
  if (ready.length === 0) return null
  return (
    <section aria-label="Ready to import" className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted">Ready to import</h2>
      <Card className="divide-y divide-border p-0">
        {ready.slice(0, 10).map((r) => (
          <div key={r.line} className="flex items-center gap-3 px-pad py-2.5 text-sm">
            <span className="w-24 shrink-0 text-muted tabular">{r.date}</span>
            <span className="min-w-0 flex-1">
              <span className="block truncate font-medium">{r.description}</span>
              <span className="block truncate text-xs text-subtle">
                {r.kind === 'TRANSFER' ? `${r.account} → ${r.toAccount}` : `${r.category} · ${r.account}`}
              </span>
            </span>
            <span className={cn('tabular shrink-0', r.kind === 'INCOME' && 'text-good-text')}>
              {r.amount !== null && money(r.kind === 'EXPENSE' ? -r.amount : r.amount, 'INR', { sign: r.kind === 'INCOME' })}
            </span>
          </div>
        ))}
        {ready.length > 10 && <p className="px-pad py-2.5 text-xs text-subtle">and {ready.length - 10} more</p>}
      </Card>
    </section>
  )
}

function PastImports() {
  const { data: imports = [] } = useImports()
  const undo = useUndoImport()
  const [confirming, setConfirming] = useState<string | null>(null)
  if (imports.length === 0) return null
  return (
    <section aria-label="Past imports" className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted">Past imports</h2>
      <Card className="divide-y divide-border p-0">
        {imports.map((i) => (
          <div key={i.id} className="flex flex-wrap items-center gap-3 px-pad py-3" data-testid={`import-${i.fileName}`}>
            <span className="min-w-0 flex-1">
              <span className="block truncate font-medium">{i.fileName}</span>
              <span className="block text-xs text-subtle">
                {new Date(i.createdAt).toLocaleString()} · {i.imported} imported
                {i.duplicates > 0 && ` · ${i.duplicates} already there`}{i.skipped > 0 && ` · ${i.skipped} skipped`}
              </span>
            </span>
            {i.undone ? <Badge>Undone</Badge> : confirming === i.id ? (
              <Button size="sm" variant="danger" disabled={undo.isPending} onClick={async () => {
                await undo.mutateAsync(i.id)
                setConfirming(null)
                toast(`Import undone`, { description: `${i.imported} transactions from ${i.fileName} were deleted.` })
              }}><Check />Delete {i.imported}</Button>
            ) : (
              <Button size="sm" variant="ghost" onClick={() => setConfirming(i.id)}><Undo2 />Undo</Button>
            )}
          </div>
        ))}
      </Card>
      <p className="text-xs text-subtle">Undo deletes every transaction the import added. <Link to="/transactions" className="underline underline-offset-4">See transactions</Link></p>
    </section>
  )
}
