import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { BadgeInfo } from './financier.models';

@Component({
  selector: 'ifp-financier-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [class]="toneClass()">{{ info().label }}</span>`,
})
export class FinancierBadgeComponent {
  readonly info = input.required<BadgeInfo>();

  protected readonly toneClass = computed(() => {
    const tone = this.info().tone;
    return tone === 'neutral' ? 'badge' : `badge badge-${tone}`;
  });
}
