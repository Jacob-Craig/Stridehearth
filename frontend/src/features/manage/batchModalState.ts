import { match } from 'ts-pattern';
import type { ActivitySport } from '../../lib/format';

export type BatchMode = 'modify' | 'delete';

/** The two-step batch confirmation dialog: configure, then confirm the affected Activities, then submit. */
export type BatchModalState =
  | { readonly step: 'closed' }
  | { readonly step: 'configure'; readonly mode: BatchMode }
  | {
      readonly step: 'confirm';
      readonly mode: BatchMode;
      readonly draftTitle: string;
      readonly draftSport: ActivitySport | null;
    }
  | { readonly step: 'submitting'; readonly mode: BatchMode };

/**
 * The title and sport being configured for a batch modify (blank / null keep each Activity's value).
 * Exactly the draft fields of the `confirm` step, so a confirm state is itself a draft.
 */
export type BatchDraft = Pick<
  Extract<BatchModalState, { step: 'confirm' }>,
  'draftTitle' | 'draftSport'
>;

export const EMPTY_DRAFT: BatchDraft = { draftTitle: '', draftSport: null };

export type BatchModalEvent =
  | { readonly type: 'open'; readonly mode: BatchMode }
  | { readonly type: 'review'; readonly draft: BatchDraft }
  | { readonly type: 'back' }
  | { readonly type: 'submit' }
  /** The server rejected the batch; return to confirmation with the draft that was submitted. */
  | { readonly type: 'failed'; readonly draft: BatchDraft }
  | { readonly type: 'succeeded' }
  | { readonly type: 'dismiss' };

export const CLOSED: BatchModalState = { step: 'closed' };

const confirm = (mode: BatchMode, { draftTitle, draftSport }: BatchDraft): BatchModalState => ({
  step: 'confirm',
  mode,
  draftTitle,
  draftSport,
});

/** Transitions the dialog; events that make no sense in the current step leave it unchanged. */
export function batchModalReducer(state: BatchModalState, event: BatchModalEvent): BatchModalState {
  return (
    match(event)
      .returnType<BatchModalState>()
      .with({ type: 'open' }, ({ mode }) =>
        state.step === 'closed' ? { step: 'configure', mode } : state,
      )
      .with({ type: 'review' }, ({ draft }) =>
        state.step === 'configure' ? confirm(state.mode, draft) : state,
      )
      .with({ type: 'back' }, () =>
        state.step === 'confirm' ? { step: 'configure', mode: state.mode } : state,
      )
      .with({ type: 'submit' }, () =>
        state.step === 'confirm' ? { step: 'submitting', mode: state.mode } : state,
      )
      .with({ type: 'failed' }, ({ draft }) =>
        state.step === 'submitting' ? confirm(state.mode, draft) : state,
      )
      .with({ type: 'succeeded' }, () => (state.step === 'submitting' ? CLOSED : state))
      // A batch in flight cannot be dismissed: its outcome must be reported.
      .with({ type: 'dismiss' }, () => (state.step === 'submitting' ? state : CLOSED))
      .exhaustive()
  );
}
