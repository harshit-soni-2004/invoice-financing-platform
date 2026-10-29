import { TestBed } from '@angular/core/testing';

import { DisburseKeysService } from './disburse-keys.service';

describe('DisburseKeysService', () => {
  let keys: DisburseKeysService;

  beforeEach(() => {
    keys = TestBed.inject(DisburseKeysService);
  });

  it('keeps one key per agreement until it is forgotten', () => {
    const first = keys.keyFor('agreement-1');
    expect(first).toMatch(/^[A-Za-z0-9_-]{8,100}$/);
    expect(keys.keyFor('agreement-1')).toBe(first);
    expect(keys.keyFor('agreement-2')).not.toBe(first);

    keys.forget('agreement-1');
    expect(keys.keyFor('agreement-1')).not.toBe(first);
  });
});
