/**
 * An API call in plain words, for the admin console: "POST /api/core/spaces/{spaceId}/transactions" → "Added a
 * transaction". The route and status stay one click away; this is the line people read first.
 */
const PHRASES: [method: string, route: RegExp, phrase: string][] = [
  // Money
  ['POST', /\/transactions$/, 'Added a transaction'],
  ['PUT', /\/transactions\/\{[^}]+\}$/, 'Edited a transaction'],
  ['DELETE', /\/transactions\/\{[^}]+\}$/, 'Deleted a transaction'],
  ['GET', /\/transactions(\/summary)?$/, 'Looked at transactions'],
  ['GET', /\/transactions\/export/, 'Downloaded transactions'],
  ['POST', /\/accounts$/, 'Added an account'],
  ['PATCH', /\/accounts\/\{[^}]+\}$/, 'Changed an account'],
  ['POST', /\/categories$/, 'Added a category'],
  ['PUT', /\/categories\/\{[^}]+\}$/, 'Changed a category'],
  ['DELETE', /\/categories\/\{[^}]+\}$/, 'Deleted a category'],
  ['POST', /\/categories\/\{[^}]+\}\/merge/, 'Merged two categories'],
  ['PUT', /\/budgets\/\{[^}]+\}$/, 'Set a budget'],
  ['DELETE', /\/budgets\/\{[^}]+\}$/, 'Removed a budget'],
  ['POST', /\/commitments$/, 'Added a bill or saving to the plan'],
  ['PUT', /\/commitments\/\{[^}]+\}$/, 'Changed a bill or saving'],
  ['DELETE', /\/commitments\/\{[^}]+\}$/, 'Removed a bill or saving'],
  ['POST', /\/commitments\/\{[^}]+\}\/payments$/, 'Marked a bill paid'],
  ['POST', /\/commitments\/\{[^}]+\}\/link/, 'Linked a payment to a bill'],
  ['DELETE', /\/commitments\/\{[^}]+\}\/payments/, 'Unlinked a bill payment'],
  ['POST', /\/goals$/, 'Started a savings goal'],
  ['POST', /\/goals\/\{[^}]+\}\/contributions/, 'Added money to a goal'],
  ['POST', /\/category-rules$/, 'Made a category rule'],
  ['POST', /\/imports/, 'Imported a file'],
  ['PUT', /\/tax\/mappings/, 'Set a tax section'],
  // Auto-capture
  ['POST', /\/capture\/paste$/, 'Pasted bank SMS'],
  ['POST', /\/capture\/statement$/, 'Imported a bank statement'],
  ['POST', /\/capture\/items\/add$/, 'Added captured entries in bulk'],
  ['POST', /\/capture\/items\/\{[^}]+\}\/add$/, 'Added an entry from auto-capture'],
  ['POST', /\/capture\/items\/\{[^}]+\}\/dismiss$/, 'Dismissed a captured entry'],
  ['POST', /\/capture\/address\/reset$/, 'Made a new forwarding address'],
  // Groups, people, households
  ['POST', /^\/api\/core\/groups$/, 'Started a group'],
  ['PATCH', /^\/api\/core\/groups\/\{[^}]+\}$/, 'Changed a group'],
  ['POST', /\/groups\/\{[^}]+\}\/members$/, 'Added someone to a group'],
  ['DELETE', /\/groups\/\{[^}]+\}\/members\//, 'Removed someone from a group'],
  ['POST', /\/groups\/\{[^}]+\}\/expenses$/, 'Split an expense in a group'],
  ['PUT', /\/groups\/\{[^}]+\}\/expenses\//, 'Edited a group expense'],
  ['DELETE', /\/groups\/\{[^}]+\}\/expenses\//, 'Deleted a group expense'],
  ['POST', /\/groups\/\{[^}]+\}\/settlements$/, 'Recorded a settle-up'],
  ['POST', /\/groups\/join/, 'Joined a group by link'],
  ['POST', /\/people\/loans$/, 'Recorded money lent or borrowed'],
  ['POST', /^\/api\/core\/households$/, 'Started a household'],
  ['POST', /\/households\/\{[^}]+\}\/invites$/, 'Invited someone to a household'],
  ['POST', /\/households\/join$/, 'Joined a household'],
  ['PATCH', /\/households\/\{[^}]+\}\/members\//, 'Changed a household role'],
  ['DELETE', /\/households\/\{[^}]+\}\/members\//, 'Left or removed someone from a household'],
  // Account and settings
  ['GET', /^\/api\/core\/me$/, 'Opened the app'],
  ['PATCH', /^\/api\/core\/me$/, 'Changed their settings'],
  ['GET', /\/me\/export$/, 'Downloaded all their data'],
  ['POST', /\/me\/import$/, 'Restored data from an export'],
  ['POST', /\/me\/delete$/, 'Deleted their account'],
  ['PUT', /\/me\/group-budget$/, 'Changed how groups count in their budget'],
  ['POST', /\/me\/plan\/trial/, 'Started the Premium trial'],
  // Staff
  ['POST', /\/admin\/users$/, 'Staff: added a user'],
  ['PATCH', /\/admin\/users\//, 'Staff: changed a user'],
  ['PUT', /\/admin\/settings/, 'Staff: changed app settings'],
  ['POST', /\/admin\/emails/, 'Staff: sent emails now'],
]

const RESOURCE: [RegExp, string][] = [
  [/budgets/, 'budgets'], [/reports|breakdown/, 'reports'], [/tax/, 'tax'], [/goals/, 'goals'], [/commitments|plan|safe-to-spend|planner/, 'the plan'],
  [/accounts/, 'accounts'], [/categories/, 'categories'], [/groups/, 'groups'], [/people/, 'people'], [/households/, 'households'],
  [/capture/, 'auto-capture'], [/inbox|notifications/, 'notifications'], [/admin/, 'the admin console'],
]

/** "POST /api/core/spaces/{spaceId}/transactions" (and its status) → "Added a transaction" (or "… failed"). */
export function describeAction(action: string | null | undefined, status?: number | null): string {
  if (!action) return 'Did something'
  const [method = '', route = ''] = action.split(' ')
  const hit = PHRASES.find(([m, r]) => m === method && r.test(route))
  let phrase = hit?.[2]
  if (!phrase) {
    const what = RESOURCE.find(([r]) => r.test(route))?.[1] ?? 'something'
    phrase = method === 'GET' ? `Looked at ${what}` : method === 'DELETE' ? `Removed something in ${what}` : `Changed ${what}`
  }
  if (status && status >= 400) return `${phrase}: failed (${status === 401 ? 'signed out' : status === 403 ? 'not allowed' : status === 404 ? 'not found'
    : status === 409 ? 'changed elsewhere' : status === 422 ? 'not valid' : status === 429 ? 'too many requests' : status >= 500 ? 'our error' : status})`
  return phrase
}
