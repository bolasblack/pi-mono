function isNonNull<T>(value: T | null): value is T {
	return value !== null;
}

export async function runInParallelStable<T, U>(
	items: readonly T[],
	worker: (item: T, index: number) => Promise<U | null>,
): Promise<U[]> {
	const results: Array<U | null> = await Promise.all(items.map((item, index) => worker(item, index)));
	return results.filter(isNonNull);
}
