import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';

const config: Config = {
  title: 'Bipros EPPM',
  tagline: 'Enterprise Project Portfolio Management — User Guide',
  favicon: 'img/favicon.ico',

  url: 'https://bipros-eppm.example.com',
  baseUrl: '/docs/',

  organizationName: 'bipros',
  projectName: 'bipros-eppm',

  onBrokenLinks: 'warn',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          routeBasePath: '/',
          remarkPlugins: [remarkMath],
          rehypePlugins: [rehypeKatex],
        },
        blog: false,
        theme: {
          customCss: [
            './src/css/custom.css',
            './node_modules/katex/dist/katex.min.css',
          ],
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    image: 'img/social-card.png',
    colorMode: {
      defaultMode: 'light',
      disableSwitch: false,
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'Bipros EPPM',
      logo: {
        alt: 'Bipros EPPM Logo',
        src: 'img/logo.svg',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'guideSidebar',
          position: 'left',
          label: 'User Guide',
        },
        {
          type: 'docSidebar',
          sidebarId: 'glossarySidebar',
          position: 'left',
          label: 'Glossary',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Getting Started',
          items: [
            { label: 'Introduction', to: '/getting-started/introduction' },
            { label: 'Navigating the App', to: '/getting-started/navigation' },
            { label: 'Quick Start', to: '/getting-started/quick-start' },
          ],
        },
        {
          title: 'Core Modules',
          items: [
            { label: 'Projects', to: '/projects/overview' },
            { label: 'Dashboards', to: '/dashboards/overview' },
            { label: 'Reports & Analytics', to: '/reports-analytics/reports' },
          ],
        },
        {
          title: 'Reference',
          items: [
            { label: 'Glossary', to: '/glossary' },
          ],
        },
      ],
      copyright: `Copyright &copy; ${new Date().getFullYear()} Bipros EPPM. All rights reserved.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
