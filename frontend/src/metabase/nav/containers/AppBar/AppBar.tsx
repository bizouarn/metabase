import { withRouter } from "react-router";
import _ from "underscore";

import { logout } from "metabase/auth/actions";
import Collections from "metabase/entities/collections";
import { connect } from "metabase/lib/redux";
import { PLUGIN_METABOT } from "metabase/plugins";
import { closeNavbar, toggleNavbar } from "metabase/redux/app";
import type { RouterProps } from "metabase/selectors/app";
import {
  getIsCollectionPathVisible,
  getIsLogoVisible,
  getIsNavBarEnabled,
  getIsNavbarOpen,
  getIsNewButtonVisible,
  getIsProfileLinkVisible,
  getIsQuestionLineageVisible,
  getIsSearchVisible,
} from "metabase/selectors/app";
import { getIsEmbeddingIframe } from "metabase/selectors/embed";
import { getUser } from "metabase/selectors/user";
import type { State } from "metabase-types/store";

import AppBar from "../../components/AppBar";

const mapStateToProps = (state: State, props: RouterProps) => ({
  currentUser: getUser(state),
  collectionId: Collections.selectors.getInitialCollectionId(state, props),
  isNavBarOpen: getIsNavbarOpen(state),
  isNavBarEnabled: getIsNavBarEnabled(state, props),
  isMetabotVisible: PLUGIN_METABOT.getMetabotVisible(state),
  isLogoVisible: getIsLogoVisible(state),
  isSearchVisible: getIsSearchVisible(state),
  isEmbeddingIframe: getIsEmbeddingIframe(state),
  isNewButtonVisible: getIsNewButtonVisible(state),
  isProfileLinkVisible: getIsProfileLinkVisible(state),
  isCollectionPathVisible: getIsCollectionPathVisible(state, props),
  isQuestionLineageVisible: getIsQuestionLineageVisible(state, props),
});

const mapDispatchToProps = {
  onToggleNavbar: toggleNavbar,
  onCloseNavbar: closeNavbar,
  onLogout: logout,
};

// eslint-disable-next-line import/no-default-export -- deprecated usage
export default _.compose(
  withRouter,
  connect(mapStateToProps, mapDispatchToProps),
)(AppBar);
