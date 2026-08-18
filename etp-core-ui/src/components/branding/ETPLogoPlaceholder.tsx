import styles from './ETPLogoPlaceholder.module.css';

const ETP_LOGO_HORIZONTAL_SRC = '/assets/etp-logo-horizontal.png';

export function ETPLogoPlaceholder() {
  return (
    <div className={styles.brandHeader}>
      <img
        src={ETP_LOGO_HORIZONTAL_SRC}
        alt="ETP — Enterprise Technology Platform"
        className={styles.logo}
      />
    </div>
  );
}
