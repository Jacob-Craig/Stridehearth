import type { ErrorComponentProps } from '@tanstack/react-router';
import { parseApiError } from '../utils/error';
import { ErrorAlert } from './ErrorAlert';

/** The error component of a route whose data could not be loaded, headed by [heading]. */
export function routeErrorComponent(heading: string) {
  return function RouteErrorComponent({ error }: ErrorComponentProps) {
    return <ErrorAlert title={heading}>{parseApiError(error).generalError}</ErrorAlert>;
  };
}
