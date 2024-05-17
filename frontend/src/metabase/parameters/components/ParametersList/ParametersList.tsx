import { PointerSensor, useSensor } from "@dnd-kit/core";
import cx from "classnames";
import { useCallback, useMemo } from "react";

import type {
  DragEndEvent,
  RenderItemProps,
} from "metabase/core/components/Sortable";
import { SortableList } from "metabase/core/components/Sortable";
import CS from "metabase/css/core/index.css";
import type { ParametersListProps } from "metabase/parameters/components/ParametersList";
import { getVisibleParameters } from "metabase/parameters/utils/ui";
import { Icon } from "metabase/ui";
import type { Parameter } from "metabase-types/api";

import { ParameterWidget } from "../ParameterWidget";

const getId = (valuePopulatedParameter: Parameter) =>
  valuePopulatedParameter.id;

export const ParametersList = ({
  className,
  commitImmediately = false,
  dashboard,
  editingParameter,
  enableParameterRequiredBehavior,
  hideParameters,
  isEditing,
  isFullscreen,
  isNightMode,
  parameters,
  question,
  setEditingParameter,
  setParameterIndex,
  setParameterValue,
  setParameterValueToDefault,
  vertical = false,
}: ParametersListProps) => {
  const pointerSensor = useSensor(PointerSensor, {
    activationConstraint: { distance: 15 },
  });

  const visibleValuePopulatedParameters = useMemo(
    () => getVisibleParameters(parameters, hideParameters),
    [parameters, hideParameters],
  );

  const handleSortEnd = useCallback(
    ({ id, newIndex }: DragEndEvent) => {
      if (setParameterIndex) {
        setParameterIndex(id as string, newIndex);
      }
    },
    [setParameterIndex],
  );

  const renderItem = ({
    item: valuePopulatedParameter,
    id,
  }: RenderItemProps<Parameter>) => (
    <ParameterWidget
      key={`sortable-${id}`}
      className={cx({ [CS.mb2]: vertical })}
      isEditing={isEditing}
      isFullscreen={isFullscreen}
      isNightMode={isNightMode}
      parameter={valuePopulatedParameter}
      parameters={parameters}
      question={question}
      dashboard={dashboard}
      editingParameter={editingParameter}
      setEditingParameter={setEditingParameter}
      setValue={
        setParameterValue &&
        ((value: string) =>
          setParameterValue(valuePopulatedParameter.id, value))
      }
      setParameterValueToDefault={setParameterValueToDefault}
      enableParameterRequiredBehavior={enableParameterRequiredBehavior}
      commitImmediately={commitImmediately}
      dragHandle={
        isEditing && setParameterIndex ? (
          <div
            className={cx(
              CS.flex,
              CS.layoutCentered,
              CS.cursorGrab,
              "text-inherit",
            )}
          >
            <Icon name="grabber" />
          </div>
        ) : null
      }
      isSortable
    />
  );

  return visibleValuePopulatedParameters.length > 0 ? (
    <div
      className={cx(
        className,
        CS.flex,
        CS.alignEnd,
        CS.flexWrap,
        vertical ? CS.flexColumn : CS.flexRow,
      )}
    >
      <SortableList
        items={visibleValuePopulatedParameters}
        getId={getId}
        renderItem={renderItem}
        onSortEnd={handleSortEnd}
        sensors={[pointerSensor]}
      />
    </div>
  ) : null;
};