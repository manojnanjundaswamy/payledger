import { describe, expect, it } from 'vitest'
import { uuid7 } from './uuid'

describe('uuid7', () => {
  it('is a version 7 UUID that sorts by time', () => {
    const a = uuid7(1_760_000_000_000)
    const b = uuid7(1_760_000_000_001)
    expect(a).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(a < b).toBe(true)
    expect(a.slice(0, 13)).toBe('0199c82c-c000')
  })
})
