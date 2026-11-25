import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { athleteThresholdsQueryOptions } from '../../api/athlete';
import { currentUserQueryOptions } from '../../auth/AuthContext';
import { SettingsPage } from './SettingsPage';

const authenticatedApi = getRouteApi('/authenticated');

/** The queries the Settings route loads and renders. */
export const settingsQueries = (userId: number | undefined) => ({
  thresholds: athleteThresholdsQueryOptions(userId),
});

export function SettingsRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const { data } = useSuspenseQuery(settingsQueries(user.id).thresholds);
  // The cached user follows a time-zone change at once; the route context keeps the user it was entered with.
  const { data: cachedUser } = useQuery({ ...currentUserQueryOptions, enabled: false });
  return (
    <SettingsPage
      userId={user.id}
      timeZone={cachedUser?.timeZone ?? user.timeZone ?? null}
      thresholds={data}
    />
  );
}
