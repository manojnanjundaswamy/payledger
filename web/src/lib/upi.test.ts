import { describe, expect, it } from 'vitest'
import { UPI_ID, upiPayLink } from './upi'

describe('UPI', () => {
  it('builds a pay link with the amount in rupees', () => {
    expect(upiPayLink('ravi.k@okhdfcbank', 'Ravi K', 60_050, 'Goa trip settle-up'))
      .toBe('upi://pay?pa=ravi.k%40okhdfcbank&pn=Ravi%20K&am=600.50&cu=INR&tn=Goa%20trip%20settle-up')
  })

  it('accepts real UPI ids and nothing else', () => {
    expect(UPI_ID.test('9876543210@ybl')).toBe(true)
    expect(UPI_ID.test('asha.r@okicici')).toBe(true)
    expect(UPI_ID.test('asha r@okicici')).toBe(false)
    expect(UPI_ID.test('asha@')).toBe(false)
  })
})
