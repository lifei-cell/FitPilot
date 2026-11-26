export function planPayload(name) {
  return JSON.stringify({
    name,
    description: 'V5 production load test plan',
    goal: 'STRENGTH',
    durationWeeks: 4,
    days: [{
      dayNumber: 1,
      name: 'Day 1',
      exercises: [{
        exerciseId: 1,
        sequence: 1,
        targetSets: 3,
        targetRepsMin: 5,
        targetRepsMax: 8,
        targetRpe: 8,
        restSeconds: 120,
      }],
    }],
  });
}
