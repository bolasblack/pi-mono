/**
 * Typed listener registry that infers event/handler pairs from an API's `on()` overloads.
 *
 * `add` has the exact same overloaded signature as `API["on"]`, so all event
 * names and handler types are preserved. `dispose()` unbinds everything.
 *
 * Usage with ExtensionAPI:
 *   const listeners = createListenerRegistry(pi);
 *   listeners.add("tool_result", async (event, ctx) => { ... });
 *   //                                  ^typed    ^typed
 */

/** Any object with an overloaded `on()` method for event subscription. */
export type EventEmitterLike = { on(...args: any): any };

export interface ListenerRegistry<API extends EventEmitterLike> {
	/** Register a listener. Same overloaded signature as `API["on"]`. */
	add: API["on"];
	/** Unbind all listeners registered via `add`. */
	dispose(): void;
}

export function createListenerRegistry<API extends EventEmitterLike>(api: API): ListenerRegistry<API> {
	const disposers: Array<() => void> = [];

	const add = (...args: any[]) => {
		const maybeDisposer = api.on(...args);

		if (typeof maybeDisposer === "function") {
			disposers.push(maybeDisposer as () => void);
			return;
		}

		if (typeof (api as any).off === "function") {
			disposers.push(() => (api as any).off(...args));
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

	return { add: add as API["on"], dispose };
}
