import { Link } from 'react-router-dom';
import { Panel } from '../components/ui/Panel';
import { Button } from '../components/ui/Button';
import { IconLanes } from '../components/icons';

export default function NotFoundPage() {
  return (
    <div className="mx-auto max-w-md pt-10">
      <Panel title="404 · lane not found">
        <div className="py-8 text-center">
          <IconLanes size={28} className="mx-auto text-dim" />
          <p className="mt-3 font-mono text-[13px] text-ink">this route does not exist</p>
          <p className="mt-1 font-mono text-[11px] text-dim">the console has no lane mapped to the requested path</p>
          <Link to="/" className="mt-5 inline-block">
            <Button variant="primary">back to operations</Button>
          </Link>
        </div>
      </Panel>
    </div>
  );
}
