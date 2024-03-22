// eslint-disable-next-line no-restricted-imports
import { List } from "@mantine/core";
import { Link } from "react-router";
import { t, jt } from "ttag";

import ExternalLink from "metabase/core/components/ExternalLink";
import { Anchor, Button, Icon, Text } from "metabase/ui";

import type { EmbedHomepageViewProps } from "./EmbedHomepageView";

export const StaticTabContent = ({
  embeddingAutoEnabled,
  exampleDashboardId,
  learnMoreStaticEmbedUrl,
}: EmbedHomepageViewProps) => {
  return (
    <>
      <Text
        mb="md"
        lh="1.5"
      >{t`Use static embedding to present data that applies to all of your tenants at once without ad hoc query access to their data. Available in all plans.`}</Text>

      <Text fw="bold" mb="md">{t`The TL;DR:`}</Text>

      <List
        type="ordered"
        mb="lg"
        size="sm"
        // TODO: fix this in a better way
        style={{ listStyleType: "decimal" }}
      >
        {embeddingAutoEnabled === false && (
          <List.Item>
            <Link to="/admin/settings/embedding-in-other-applications">
              <Anchor size="sm">{t`Enable embedding in the settings`}</Anchor>
            </Link>
          </List.Item>
        )}
        <List.Item>{jt`${
          exampleDashboardId !== undefined ? t`Select` : `Create`
        } a question or dashboard to embed. Then click ${(
          <strong key="bold">{t`share`}</strong>
        )}`}</List.Item>
        <List.Item>{t`Configure the parameters availability (editable, disabled or locked) in your app's code.`}</List.Item>
        <List.Item>{t`Publish the dashboard or question`}</List.Item>
        <List.Item>{t`Add code to your app to sign a token for the embed request. Include values for locked parameters if you have any`}</List.Item>
        <List.Item>{t`Embed the dashboard into your app using an iframe, the URL and the signed token. `}</List.Item>
      </List>

      {exampleDashboardId && (
        <Link to={`/dashboard/${exampleDashboardId}`}>
          <Button
            variant="filled"
            mb="sm"
            leftIcon={<Icon name="dashboard" />}
          >{t`Embed this example dashboard`}</Button>
        </Link>
      )}
      <Text>{jt`${(
        <ExternalLink
          key="learn-more"
          href={learnMoreStaticEmbedUrl}
        >{t`Learn more`}</ExternalLink>
      )} about static embedding`}</Text>
    </>
  );
};