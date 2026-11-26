import { queryOptions } from "@tanstack/react-query";
import { api, ApiError } from "../../api/client";
import type { Plan } from "../../api/types";

const TRAINING_PLAN_NOT_FOUND = 30001;

export const activePlanQuery = queryOptions({
  queryKey: ["active-plan"],
  queryFn: async ({ signal }) => {
    try {
      return await api<Plan>("/training-plans/active/current", { signal });
    } catch (error) {
      if (
        error instanceof ApiError &&
        error.status === 404 &&
        error.code === TRAINING_PLAN_NOT_FOUND
      ) {
        return null;
      }
      throw error;
    }
  },
  retry: false,
});
