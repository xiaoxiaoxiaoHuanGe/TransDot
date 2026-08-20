export type RebindStatus = 'pending' | 'expired' | 'consumed'

export function rebindScreenForStatus(status: RebindStatus) {
  if (status === 'consumed') return 'paired' as const
  if (status === 'expired') return 'expired' as const
  return 'rebind' as const
}
