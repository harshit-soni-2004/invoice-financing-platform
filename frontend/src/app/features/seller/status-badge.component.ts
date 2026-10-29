import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { InvoiceStatus, STATUS_INFO } from './invoice.models';

@Component({
  selector: 'ifp-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [class]="toneClass()">{{ info().label }}</span>`,
})
export class StatusBadgeComponent {
  readonly status = input.required<InvoiceStatus>();

  protected readonly info = computed(() => STATUS_INFO[this.status()]);
  protected readonly toneClass = computed(() => {
    const tone = this.info().tone;
    return tone === 'neutral' ? 'badge' : `badge badge-${tone}`;
  });
}
