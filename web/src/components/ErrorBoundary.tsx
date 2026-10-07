import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Panel } from './ui/Panel';
import { Button } from './ui/Button';
import { IconAlert } from './icons';

interface State {
  error: Error | null;
}

/** Last-resort crash panel — keeps the console from white-screening. */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('[taskmesh] UI crash:', error, info.componentStack);
  }

  render(): ReactNode {
    if (this.state.error != null) {
      return (
        <div className="mx-auto max-w-xl px-4 pt-16">
          <Panel title="console error">
            <div className="flex items-start gap-3 py-3">
              <IconAlert size={18} className="mt-0.5 shrink-0 text-rose" />
              <div className="min-w-0">
                <p className="font-mono text-[12px] text-ink">the console hit an unexpected error</p>
                <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap rounded-sm border border-line bg-bg-deep p-2 font-mono text-[10.5px] leading-4 text-rose scroll-thin">
                  {this.state.error.message}
                </pre>
                <div className="mt-3 flex gap-2">
                  <Button variant="primary" onClick={() => window.location.reload()}>
                    reload console
                  </Button>
                  <Button variant="secondary" onClick={() => this.setState({ error: null })}>
                    try again
                  </Button>
                </div>
              </div>
            </div>
          </Panel>
        </div>
      );
    }
    return this.props.children;
  }
}
