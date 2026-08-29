import { Link } from 'react-router-dom';
import styles from './ShellBrandBlock.module.css';

const ETP_LOGO_HORIZONTAL_SRC = '/assets/etp-logo-horizontal.png';

export function ShellBrandBlock() {
  return (
    <Link to="/dashboard" className={styles.brandBlock} aria-label="ETP home">
      <img
        src={ETP_LOGO_HORIZONTAL_SRC}
        alt="ETP — Enterprise Technology Platform"
        className={styles.brandLogo}
      />
    </Link>
  );
}
