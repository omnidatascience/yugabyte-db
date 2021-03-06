// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.

#ifndef YB_SERVER_PGSQL_WEBSERVER_WRAPPER_H
#define YB_SERVER_PGSQL_WEBSERVER_WRAPPER_H

#include <stdatomic.h>
#include "yb/util/ybc_util.h"

#ifdef __cplusplus
extern "C" {
#endif

struct WebserverWrapper;

typedef struct ybpgmEntry {
    char name[100];
    atomic_ulong calls;
    atomic_ulong total_time;
} ybpgmEntry;

struct WebserverWrapper *CreateWebserver(char *listen_addresses, int port);
void RegisterMetrics(ybpgmEntry *tab, int num_entries, char *metric_node_name);
YBCStatus StartWebserver(struct WebserverWrapper *webserver);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif // YB_SERVER_PGSQL_WEBSERVER_WRAPPER_H
