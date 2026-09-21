# v0.3 Agent Protocol

The planner receives a fresh world snapshot and returns exactly one JSON action.

Supported actions:

```json
{"action":"move_to","x":0,"y":64,"z":0,"status":""}
{"action":"mine","x":0,"y":64,"z":0,"status":""}
{"action":"craft","item":"minecraft:oak_planks","count":4,"status":""}
{"action":"equip","item":"minecraft:wooden_pickaxe","status":""}
{"action":"attack","entity_id":123,"status":""}
{"action":"say","message":"hey","status":""}
{"action":"wait","ticks":20,"status":""}
{"action":"finish_goal","status":"done"}
```

Normal player chat goes through a separate lightweight conversation/intention pass. It returns:

```json
{"reply":true,"message":"sure, give me a sec","instruction":true,"goal":"Collect nearby logs for the owner."}
```

Only the owner's chat can replace the active goal.
