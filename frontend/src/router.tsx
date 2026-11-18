import type { QueryClient } from '@tanstack/react-query';
import {
  Outlet,
  type RouterHistory,
  createRootRouteWithContext,
  createRoute,
  createRouter,
  redirect,
} from '@tanstack/react-router';
import { z } from 'zod';
import { registrationStatusQueryOptions } from './api/auth';
import type { UserResponseDto } from './api/generated/types.gen';
import { heatmapQueries } from './api/heatmap';
import { inviteStatusQueryOptions } from './api/invites';
import { currentUserQueryOptions } from './auth/AuthContext';
import { ActivitiesRoute, activitiesQueries } from './components/ActivitiesRoute';
import { AddActivityRoute } from './components/AddActivityRoute';
import { AuthenticatedLayout } from './components/AuthenticatedLayout';
import { DashboardRoute, dashboardQueries } from './components/DashboardRoute';
import { EditActivityRoute, editActivityQueries } from './components/EditActivityRoute';
import { InviteRoute, InviteUnusableNotice } from './components/InviteRoute';
import { LoginRoute } from './components/LoginRoute';
import { RegisterRoute } from './components/RegisterRoute';
import { routeErrorComponent } from './components/routeErrorComponent';
import {
  ActivityDetailRoute,
  activityDetailQueries,
} from './features/activity-detail/ActivityDetailRoute';
import { HeatmapRoute } from './features/heatmap/HeatmapRoute';
import { ImportHistoryRoute, importHistoryQueries } from './features/import/ImportHistoryRoute';
import { ImportRoute } from './features/import/ImportRoute';
import { InvitesRoute, invitesQueries } from './features/invites/InvitesRoute';
import {
  ManageActivitiesRoute,
  manageActivitiesQueries,
} from './features/manage/ManageActivitiesRoute';
import { manageSearchSchema, toManageFilters } from './features/manage/manageSearch';

export interface RouterContext {
  queryClient: QueryClient;
  auth: {
    user: UserResponseDto | null;
    isLoading: boolean;
    logout?: () => Promise<void>;
    setUser?: (user: UserResponseDto | null) => void;
  };
}

export const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Outlet,
});

const loginSearchSchema = z.object({
  redirect: z.string().optional(),
});

export const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  validateSearch: (search) => loginSearchSchema.parse(search),
  component: LoginRoute,
});

export const registerRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/register',
  // The sign-up form shows only once the Server says it would accept a sign-up.
  loader: ({ context }) => context.queryClient.ensureQueryData(registrationStatusQueryOptions),
  component: RegisterRoute,
  errorComponent: routeErrorComponent('Registration could not be checked'),
});

export const inviteRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/invite/$token',
  // The sign-up form shows only for a pending Invite; any other link opens a notice saying why not.
  loader: ({ context, params }) =>
    context.queryClient.ensureQueryData(inviteStatusQueryOptions(params.token)),
  component: InviteRoute,
  errorComponent: InviteUnusableNotice,
});

export const authenticatedRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: 'authenticated',
  beforeLoad: async ({ context, location }) => {
    // The query cache is the source of truth for the current user: `auth` in the router context
    // lags it by a render, so a navigation straight after login (`setUser` then `navigate`) would
    // otherwise still see the signed-out user and bounce back to /login.
    let user = context.auth.user;
    if (user === null) {
      user = context.auth.isLoading
        ? await context.queryClient.ensureQueryData(currentUserQueryOptions)
        : (context.queryClient.getQueryData(currentUserQueryOptions.queryKey) ?? null);
    }
    if (!user) {
      throw redirect({
        to: '/login',
        search: {
          redirect: location.href,
        },
      });
    }
    return { user };
  },
  component: AuthenticatedLayout,
});

export const indexRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/dashboard' });
  },
});

export const dashboardRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/dashboard',
  loader: ({ context }) => {
    const queries = dashboardQueries(context.user.id);
    // The progression is fetched alongside but never fails the route: its card shows its own error.
    return Promise.all([
      context.queryClient.ensureQueryData(queries.dashboard),
      context.queryClient.prefetchQuery(queries.progression),
    ]);
  },
  component: DashboardRoute,
  errorComponent: routeErrorComponent('Your Dashboard could not be loaded'),
});

const activitiesSearchSchema = z.object({
  page: z.number().int().min(0).optional().catch(undefined),
});

export const activitiesRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/activities',
  validateSearch: (search) => activitiesSearchSchema.parse(search),
  loaderDeps: ({ search }) => ({ page: search.page ?? 0 }),
  loader: ({ context, deps }) =>
    context.queryClient.ensureQueryData(activitiesQueries(context.user.id, deps).activityPage),
  component: ActivitiesRoute,
  errorComponent: routeErrorComponent('Your Activities could not be loaded'),
});

export const activitiesManageRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/activities/manage',
  validateSearch: (search) => manageSearchSchema.parse(search),
  loaderDeps: ({ search }) => toManageFilters(search),
  loader: ({ context, deps }) =>
    context.queryClient.ensureQueryData(
      manageActivitiesQueries(context.user.id, deps).activityPage,
    ),
  component: ManageActivitiesRoute,
  errorComponent: routeErrorComponent('Your Activities could not be loaded'),
});

const importSearchSchema = z.object({
  batchId: z.coerce.number().int().positive().optional(),
});

export const activitiesImportRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/activities/import',
  validateSearch: (search) => importSearchSchema.parse(search),
  component: ImportRoute,
});

export const activitiesImportHistoryRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/activities/import/history',
  loader: ({ context }) =>
    context.queryClient.ensureQueryData(importHistoryQueries(context.user.id).history),
  component: ImportHistoryRoute,
  errorComponent: routeErrorComponent('Your import history could not be loaded'),
});

export const activitiesAddActivityRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/activities/add_activity',
  component: AddActivityRoute,
});

export const activityDetailRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/activities/$activityId',
  // Both requests start together (on link hover or focus, via intent preloading), so opening an
  // Activity never waits on a summary-then-telemetry waterfall.
  loader: ({ context, params }) => {
    const queries = activityDetailQueries(context.user.id, params);
    return Promise.all([
      context.queryClient.ensureQueryData(queries.activity),
      context.queryClient.ensureQueryData(queries.telemetry),
    ]);
  },
  component: ActivityDetailRoute,
  errorComponent: routeErrorComponent('This Activity could not be loaded'),
});

export const activityEditRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/activities/$activityId/edit',
  loader: ({ context, params }) =>
    context.queryClient.ensureQueryData(editActivityQueries(context.user.id, params).activity),
  component: EditActivityRoute,
  errorComponent: routeErrorComponent('This Activity could not be loaded'),
});

export const heatmapRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/heatmap',
  loader: ({ context }) => {
    const queries = heatmapQueries(context.user.id);
    return Promise.all([
      context.queryClient.ensureQueryData(queries.renderer),
      context.queryClient.ensureQueryData(queries.revision),
      context.queryClient.ensureQueryData(queries.startingView),
    ]);
  },
  component: HeatmapRoute,
  errorComponent: routeErrorComponent('Your Heatmap could not be loaded'),
});

export const invitesRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/invites',
  // Only the Administrator manages Invites; the Server refuses everyone else too.
  beforeLoad: ({ context }) => {
    if (context.user.administrator !== true) {
      throw redirect({ to: '/dashboard' });
    }
  },
  loader: ({ context }) =>
    context.queryClient.ensureQueryData(invitesQueries(context.user.id).pending),
  component: InvitesRoute,
  errorComponent: routeErrorComponent('Your invites could not be loaded'),
});

export function createAppRouter(context: RouterContext, history?: RouterHistory) {
  const routeTree = rootRoute.addChildren([
    loginRoute,
    registerRoute,
    inviteRoute,
    authenticatedRoute.addChildren([
      indexRoute,
      dashboardRoute,
      activitiesRoute,
      activitiesManageRoute,
      activitiesImportRoute,
      activitiesImportHistoryRoute,
      activitiesAddActivityRoute,
      activityDetailRoute,
      activityEditRoute,
      heatmapRoute,
      invitesRoute,
    ]),
  ]);

  return createRouter({
    routeTree,
    context,
    ...(history ? { history } : {}),
    defaultPreload: 'intent',
    defaultViewTransition: true,
  });
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
