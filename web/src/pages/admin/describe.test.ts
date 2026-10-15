import { describe, expect, it } from 'vitest'
import { describeAction } from './describe'

describe('describeAction', () => {
  it('says what happened, in plain words', () => {
    expect(describeAction('POST /api/core/spaces/{spaceId}/transactions', 201)).toBe('Added a transaction')
    expect(describeAction('DELETE /api/core/spaces/{spaceId}/transactions/{id}', 204)).toBe('Deleted a transaction')
    expect(describeAction('POST /api/core/groups/{groupId}/expenses', 201)).toBe('Split an expense in a group')
    expect(describeAction('POST /api/core/groups', 201)).toBe('Started a group')
    expect(describeAction('PATCH /api/core/me', 200)).toBe('Changed their settings')
  })
  it('says when it failed, and why in a word', () => {
    expect(describeAction('PUT /api/core/spaces/{spaceId}/transactions/{id}', 409)).toBe('Edited a transaction: failed (changed elsewhere)')
    expect(describeAction('POST /api/core/spaces/{spaceId}/accounts', 500)).toBe('Added an account: failed (our error)')
  })
  it('still says something sensible for a call it has no phrase for', () => {
    expect(describeAction('GET /api/core/spaces/{spaceId}/reports/breakdown', 200)).toBe('Looked at reports')
    expect(describeAction('POST /api/core/something/new', 200)).toBe('Changed something')
  })
})
