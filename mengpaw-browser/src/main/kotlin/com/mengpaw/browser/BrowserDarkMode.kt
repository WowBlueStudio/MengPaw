// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser

val DARK_MODE_CSS = "(function(){if(window.__mpDark)return;window.__mpDark=true;var s=document.createElement('style');s.id='mp-dark';s.textContent='html{background:#1a1a1a!important}body{color:#ddd!important;background:#1a1a1a!important}img,video,canvas,svg{opacity:.92!important}input,textarea,select,button{background:#333!important;color:#ddd!important;border-color:#555!important}code,pre{background:#333!important;color:#ccc!important}table{background:#222!important;color:#ddd!important}a{color:#6cb6ff!important}';document.head.appendChild(s);document.body&&document.body.setAttribute('data-mp-dark','true')})();"
