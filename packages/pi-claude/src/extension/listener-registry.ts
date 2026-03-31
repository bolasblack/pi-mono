/**
 * Typed listener registry that infers event/handler pairs from an API's `on()` overloads.
 *
 * Usage with ExtensionAPI:
 *   const listeners = createListenerRegistry(pi);
 *   listeners.add("tool_result", async (event, ctx) => { ... });
 *   //                                  ^typed    ^typed
 */

/** Any object with an overloaded `on()` method for event subscription. */
export type EventEmitterLike = { on(...args: any): any };

/** Extract the union of [event, handler] pairs from overloaded `on()`. */
type OnParams<API extends EventEmitterLike> = Parameters<API["on"]>;

/** Extract event name union from the API's `on()` overloads. */
type EventName<API extends EventEmitterLike> = OnParams<API>[0];

/** Extract the handler type for a specific event name. */
type HandlerFor<API extends EventEmitterLike, E extends EventName<API>> = Extract<OnParams<API>, [E, any]>[1];

export interface ListenerRegistry<API extends EventEmitterLike> {
	add<E extends EventName<API>>(event: E, handler: HandlerFor<API, E>): void;
	dispose(): void;
}

export function createListenerRegistry<API extends EventEmitterLike>(api: API): ListenerRegistry<API> {
	const disposers: Array<() => void> = [];

	const add = <E extends EventName<API>>(event: E, handler: HandlerFor<API, E>) => {
		const maybeDisposer = (api as any).on(event, handler);

		if (typeof maybeDisposer === "function") {
			disposers.push(maybeDisposer as () => void);
			return;
		}

		if (typeof (api as any).off === "function") {
			disposers.push(() => (api as any).off(event, handler));
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
