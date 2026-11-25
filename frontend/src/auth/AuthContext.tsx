import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createContext, type ReactNode, useContext } from 'react';
import { getCurrentUser, logoutUser } from '../api/auth';
import type { UserResponseDto } from '../api/generated/types.gen';

export interface AuthContextValue {
  user: UserResponseDto | null;
  isLoading: boolean;
  logout: () => Promise<void>;
  setUser: (user: UserResponseDto | null) => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export const currentUserQueryOptions = {
  queryKey: ['currentUser'] as const,
  queryFn: async (): Promise<UserResponseDto | null> => {
    try {
      return await getCurrentUser();
    } catch {
      return null;
    }
  },
  staleTime: 5 * 60 * 1000,
  retry: false,
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();

  const { data: user = null, isLoading } = useQuery(currentUserQueryOptions);

  const logoutMutation = useMutation({
    mutationFn: logoutUser,
    onSuccess: () => {
      queryClient.setQueryData(currentUserQueryOptions.queryKey, null);
    },
  });

  const value: AuthContextValue = {
    user,
    isLoading,
    logout: async () => {
      await logoutMutation.mutateAsync();
    },
    setUser: (newUser) => {
      queryClient.setQueryData(currentUserQueryOptions.queryKey, newUser);
    },
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
