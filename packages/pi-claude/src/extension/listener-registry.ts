export interface EventAPI {
	on(event: string, handler: (...args: any[]) => any): unknown;
	off?: (event: string, handler: (...args: any[]) => any) => void;
}

export function createListenerRegistry(api: EventAPI): {
	add: (event: string, handler: (...args: any[]) => any) => void;
	dispose: () => void;
} {
	const disposers: Array<() => void> = [];

	const add = (event: string, handler: (...args: any[]) => any) => {
		const maybeDisposer = api.on(event, handler);

		if (typeof maybeDisposer === "function") {
			disposers.push(maybeDisposer as () => void);
			return;
		}

		if (typeof api.off === "function") {
			disposers.push(() => api.off?.(event, handler));
		}
	};

	const dispose = () => {
		while (disposers.length > 0) {
			const off = disposers.pop()!;
			try {
				off();
			} catch {
				// ignore unbind errors on dispose
			}
		}
	};

	return { add, dispose };
}
